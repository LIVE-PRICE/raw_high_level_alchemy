package net.runelite.client.plugins.microbot.highlevelalchemy;

import net.runelite.api.*;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.shared.AutomationInteractionCoordinator;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeAction;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeRequest;
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeSlots;
import net.runelite.client.plugins.microbot.util.grandexchange.Rs2GrandExchange;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.WorldService;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;
import net.runelite.client.ui.*;
import net.runelite.http.api.worlds.WorldResult;
import javax.inject.Inject;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;

@PluginDescriptor(name="HIGH_LEVEL_ALCHEMY", description="Live-price alchemy and GE restocking", tags={"alchemy","alch","grand exchange"},
        enabledByDefault=false,isExternal=true,version=HighLevelAlchemyPlugin.VERSION,minClientVersion="2.6.34")
public final class HighLevelAlchemyPlugin extends Plugin {
    static final String VERSION = "1.0.25";
    private static final long INTERACTION_LEASE_MS = 30_000L;
    private static final int FIRE_STAFF = 1387;
    @Inject private Client client;
    @Inject private ClientToolbar toolbar;
    @Inject private ConfigManager config;
    @Inject private net.runelite.client.ui.overlay.OverlayManager overlayManager;
    private final AlchemyProfit profit=new AlchemyProfit();
    private final GeTradeHistoryDatabase tradeHistory=new GeTradeHistoryDatabase();
    private AlchemyProfitOverlay profitOverlay;
    private boolean profitInitialized;
    private String savedProfit;
    private final List<AlchemyPolicy.Item> items=AlchemyPolicy.load();
    private final Map<Integer,AlchemyPolicy.Item> byId=new LinkedHashMap<>();
    private Map<Integer, AlchemyPolicy.Item> addedMembersItems;
    private final Map<Integer,Long> cooldown=new HashMap<>();
    private final Map<Integer,String> collectedProgress=new HashMap<>();
    private final Map<Integer,String> purchaseComparisonProgress=new HashMap<>();
    private final RunCounters counters=new RunCounters();
    private final AlchemyOfferTracker offerTracker = new AlchemyOfferTracker();
    private final Map<Integer, Long> withdrawalRetry = new HashMap<>();
    private LivePrices prices;
    private ScheduledExecutorService worker;
    private volatile Future<?> runTask;
    private ScheduledExecutorService priceWorker;
    private volatile boolean running;
    private volatile String status="Stopped";
    private long lastDiagnosticAt;
    private String diagnosticContext="No game snapshot yet";
    private NavigationButton navigation;
    private JTextArea display;
    private javax.swing.Timer timer;
    private boolean bankSeen;
    private long account, nextBuy, lastSave, nextService, nextCollectionAttempt, nextHopAttempt, nextWithdrawal;
    private int lastWorld;
    private int ownedNaturePrice;
    private final Random selectionRandom=new Random();
    private final OverlapAlcher caster=new OverlapAlcher();
    private final AlchemyFailureGuard failureGuard=new AlchemyFailureGuard();
    private long nextRecoveryAt;
    private int selectedAlch=-1,castingItemId=-1,castingPrice,castingNaturePrice;
    private enum HistoryLookup { IDLE, OPENING_EXCHANGE, OPENING_HISTORY, EXITING_HISTORY, DONE }
    private HistoryLookup historyLookup=HistoryLookup.IDLE;
    private long historyDeadline;
    private boolean smallestMode;
    private volatile String variables="";
    private volatile String lastPurchaseComparison="";

    public HighLevelAlchemyPlugin() {
        for(AlchemyPolicy.Item i:items) byId.put(i.id,i);
        LoggerFactory.getLogger(getClass()).info("HIGH_LEVEL_ALCHEMY {} loaded; automation stopped; {} base items",VERSION,items.size());
    }
    @Override protected void startUp() {
        profitOverlay=new AlchemyProfitOverlay(client,profit);overlayManager.add(profitOverlay);
        SwingUtilities.invokeLater(()->{
            PluginPanel panel=new PluginPanel(){}; panel.setLayout(new BorderLayout());
            JPanel buttons=new JPanel(); JButton start=new JButton("Start"), stop=new JButton("Stop");
            start.addActionListener(e->startRun()); stop.addActionListener(e->stopRun());buttons.add(start);buttons.add(stop);
            display=new JTextArea("Stopped\nStart at the GE with level 55 Magic.");
            display.setEditable(false);display.setLineWrap(true);display.setWrapStyleWord(true);
            display.setOpaque(false);
            display.setMargin(new Insets(8,4,8,4));
            ((javax.swing.text.DefaultCaret)display.getCaret()).setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
            // PluginPanel already supplies the sidebar's full-height scroll pane.
            panel.add(buttons,BorderLayout.NORTH);panel.add(display,BorderLayout.CENTER);
            BufferedImage icon=new BufferedImage(24,24,BufferedImage.TYPE_INT_ARGB);Graphics2D g=icon.createGraphics();
            g.setColor(Color.ORANGE);g.fillOval(2,2,20,20);g.setColor(Color.BLACK);g.drawString("A",8,17);g.dispose();
            navigation=NavigationButton.builder().tooltip("HIGH_LEVEL_ALCHEMY").icon(icon).panel(panel).priority(7).build();
            toolbar.addNavigation(navigation);
            timer=new javax.swing.Timer(500,e->{
                checkWorker();
                String comparison=lastPurchaseComparison.isEmpty()?"":"\n\n"+lastPurchaseComparison;
                String text=status+"\n\n"+counters.panel()+comparison+"\n\n"+variables;
                if(!text.equals(display.getText())){display.setText(text);display.revalidate();}
            });timer.start();
        });
    }
    private synchronized void startRun() {
        if(running) return;
        if(worker!=null && !worker.isTerminated()){status="Waiting for previous operation to stop";return;}
        counters.reset();profit.reset();profitInitialized=false;if(profitOverlay!=null)profitOverlay.reset();logCounters("Run started");
        bankSeen=false;account=0;cooldown.clear();collectedProgress.clear();purchaseComparisonProgress.clear();lastPurchaseComparison="";caster.reset();failureGuard.reset();nextRecoveryAt=0;selectedAlch=-1;castingItemId=-1;castingPrice=0;castingNaturePrice=0;historyLookup=HistoryLookup.IDLE;historyDeadline=0;nextService=0;nextCollectionAttempt=0;running=true;status="Starting; checking bank, GE history, and live prices";
        AutomationInteractionCoordinator.setAutomationActive(AutomationInteractionCoordinator.HIGH_LEVEL_ALCHEMY,true);
        lastWorld = 0;
        nextHopAttempt = 0;
        nextWithdrawal = 0;
        withdrawalRetry.clear();
        if(prices==null)prices = new LivePrices();
        priceWorker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "HIGH_LEVEL_ALCHEMY prices");
            thread.setDaemon(true);
            return thread;
        });
        priceWorker.scheduleWithFixedDelay(prices::refresh, 0, 60, TimeUnit.SECONDS);
        priceWorker.scheduleWithFixedDelay(prices::refreshMembersItems, 0, 5, TimeUnit.SECONDS);
        worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"HIGH_LEVEL_ALCHEMY");t.setDaemon(true);return t;});
        lastDiagnosticAt=0;diagnosticContext="No game snapshot yet";
        runTask=worker.scheduleWithFixedDelay(this::passWithDiagnostics,0,600,TimeUnit.MILLISECONDS);
    }
    void checkWorker() {
        Future<?> task = runTask;
        if (!running || task == null || !task.isDone()) return;
        Throwable failure = null;
        try {
            task.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        } catch (ExecutionException failed) {
            failure = failed.getCause();
        } catch (CancellationException cancelled) {
            failure = cancelled;
        }
        stopRun();
        String reason = failure == null ? "Worker ended unexpectedly"
            : failure.getClass().getSimpleName() + ": " + failure.getMessage();
        status = "Stopped: " + reason;
        LoggerFactory.getLogger(getClass()).error("HIGH_LEVEL_ALCHEMY worker ended unexpectedly", failure);
    }
    private synchronized void stopRun(){if(running)logCounters("Run stopped");running=false;status="Stopped";AutomationInteractionCoordinator.setAutomationActive(AutomationInteractionCoordinator.HIGH_LEVEL_ALCHEMY,false);AutomationInteractionCoordinator.release(AutomationInteractionCoordinator.HIGH_LEVEL_ALCHEMY);if(worker!=null)worker.shutdownNow();if(priceWorker!=null)priceWorker.shutdownNow();}
    @Override protected void shutDown(){stopRun();if(profitOverlay!=null)overlayManager.remove(profitOverlay);SwingUtilities.invokeLater(()->{if(timer!=null)timer.stop();if(navigation!=null)toolbar.removeNavigation(navigation);});}
    private boolean active(){return running&&!Thread.currentThread().isInterrupted()&&!Microbot.pauseAllScripts.get();}
    private static final class Offer {
        int slot,id,quantity,traded;long spent,price;GrandExchangeOfferState state;
        String name;
        boolean empty(){return state==GrandExchangeOfferState.EMPTY;}
        boolean buying(){return state==GrandExchangeOfferState.BUYING;}
        boolean collectable(){return state==GrandExchangeOfferState.BOUGHT || state==GrandExchangeOfferState.SOLD || state==GrandExchangeOfferState.CANCELLED_BUY || state==GrandExchangeOfferState.CANCELLED_SELL;}
    }
    private static final class Snapshot {
        boolean loggedIn,membersWorld,hasUnnotedFireStaff;int days,magic,world,notedFireStaff=-1;long account;
        Collection<AlchemyPolicy.Item> candidates;
        Map<Integer,Long> inventory=new HashMap<>(),bank=new HashMap<>(),equipment=new HashMap<>();
        Map<Integer,Integer> inventoryIds=new HashMap<>();List<Offer> offers=new ArrayList<>();
        Set<Integer> notableBankItems = new HashSet<>();
        long ownedValue;int fireStaff,freeSlots=28;
        boolean includesMembersItems() { return AlchemyPolicy.includesMembersItems(days, membersWorld); }
        long count(Map<Integer,Long> bag,int id){return bag.getOrDefault(id,0L);}
        long coins(){return count(inventory,995)+count(bank,995);}
        boolean hasOffer(int id){return offers.stream().anyMatch(o->!o.empty()&&o.id==id);}
        int occupied(){return (int)offers.stream().filter(o->!o.empty()).count();}
    }
    private void readContainer(Snapshot s,InventoryID type,Map<Integer,Long> bag) {
        ItemContainer container=client.getItemContainer(type);if(container==null)return;
        for(Item item:container.getItems()) {
            if(item.getId()<0||item.getQuantity()<=0)continue;
            if(type==InventoryID.INVENTORY)s.freeSlots--;
            ItemComposition def=client.getItemDefinition(item.getId());
            if(def.getPlaceholderTemplateId()!=-1)continue;
            int id=def.getNote()!=-1 ? def.getLinkedNoteId():item.getId();
            if (!s.includesMembersItems() && (def.isMembers() || (id != item.getId()
                    && client.getItemDefinition(id).isMembers()))) continue;
            bag.merge(id,(long)item.getQuantity(),Long::sum);
            if (type == InventoryID.BANK && (id != item.getId() || def.getLinkedNoteId() != -1)) {
                s.notableBankItems.add(id);
            }
            if(type==InventoryID.INVENTORY){
                if (item.getId() == FIRE_STAFF) s.hasUnnotedFireStaff = true;
                else if (id == FIRE_STAFF) s.notedFireStaff = item.getId();
                // Keep the current exact noted/unnoted form while it remains available.
                // Dragging a stack must not change the chosen form through inventory order.
                if(item.getId()==castingItemId)s.inventoryIds.put(id,item.getId());
                else s.inventoryIds.putIfAbsent(id,item.getId());
            }
        }
    }
    private Snapshot snapshot() {
        return Microbot.getClientThread().invoke(()->{
            Snapshot s=new Snapshot();s.loggedIn=client.getGameState()==GameState.LOGGED_IN;
            if(!s.loggedIn)return s;
            s.account=client.getAccountHash();s.days=client.getVarpValue(VarPlayer.MEMBERSHIP_DAYS);
            s.magic=client.getRealSkillLevel(Skill.MAGIC);s.membersWorld=client.getWorldType().contains(WorldType.MEMBERS);
            s.world = client.getWorld();
            if (s.days < 0 || AlchemyPolicy.needsMembersWorld(s.days, s.membersWorld)) return s;
            s.candidates = s.includesMembersItems() ? byId.values() : items;
            readContainer(s,InventoryID.INVENTORY,s.inventory);readContainer(s,InventoryID.BANK,s.bank);readContainer(s,InventoryID.EQUIPMENT,s.equipment);
            for(AlchemyPolicy.Item i:s.candidates)s.ownedValue+=i.highAlch*(s.count(s.inventory,i.id)+s.count(s.bank,i.id)+s.count(s.equipment,i.id));
            for(int id:new int[]{1387,1393,1401,3053,3054,11787,11789,20730,20733})if(s.count(s.equipment,id)>0)s.fireStaff=id;
            GrandExchangeOffer[] offers=client.getGrandExchangeOffers();
            for(int n=0;n<Math.min(AlchemyPolicy.geSlots(s.days),offers.length);n++){
                GrandExchangeOffer ge=offers[n];if(ge==null)continue;
                Offer o=new Offer();o.slot=n;o.id=ge.getItemId();o.state=ge.getState();o.quantity=ge.getTotalQuantity();o.traded=ge.getQuantitySold();o.spent=ge.getSpent();o.price=ge.getPrice();if(o.collectable()&&o.traded>0){
                    ItemComposition definition=client.getItemDefinition(o.id);
                    o.name=definition==null?String.valueOf(o.id):definition.getName();
                }
                s.offers.add(o);
            }
            return s;
        });
    }
    private void hopToMembersWorld(long now) {
        if (now < nextHopAttempt || Microbot.isHopping() || !active()) return;
        nextHopAttempt = now + 15_000;
        WorldService service = Microbot.getWorldService();
        WorldResult worlds = service == null ? null : service.getWorlds();
        net.runelite.http.api.worlds.World target = worlds == null ? null : worlds.getWorlds().stream()
            .filter(world -> world.getTypes().equals(EnumSet.of(net.runelite.http.api.worlds.WorldType.MEMBERS)))
            .filter(world -> world.getPlayers() >= 0 && world.getPlayers() < 1950)
            .min(Comparator.comparingInt(net.runelite.http.api.worlds.World::getPlayers))
            .orElse(null);
        if (target == null) {
            status = "Waiting for an available members world";
            return;
        }
        status = "Switching to members world " + target.getId();
        closeBank();
        closeGe();
        if (active() && !Microbot.hopToWorld(target.getId())) {
            status = "Could not switch to members world " + target.getId() + "; retrying shortly";
        }
    }
    private void loadLedger(long hash) {
        cooldown.clear();String raw=config.getConfiguration("highlevelalchemy","limits_"+hash);
        if(raw!=null) for(String entry:raw.split(";")) {
            if(entry.isEmpty())continue;String[] pair=entry.split(":");
            if(pair.length!=2)throw new IllegalStateException("Invalid saved buy-limit ledger");
            cooldown.put(Integer.parseInt(pair[0]),Long.parseLong(pair[1]));
        }
    }
    private void saveLedger(long now) {
        StringBuilder text=new StringBuilder();cooldown.forEach((id,until)->{if(until>now)text.append(id).append(':').append(until).append(';');});
        config.setConfiguration("highlevelalchemy","limits_"+account,text.toString());lastSave=now;
    }
    private void passWithDiagnostics() {
        try { pass(); }
        finally {
            long now=System.currentTimeMillis();
            if(running && now-lastDiagnosticAt>=30000) {
                lastDiagnosticAt=now;
                LoggerFactory.getLogger(getClass()).info("HIGH_LEVEL_ALCHEMY diagnostic: status={}; paused={}; {}",
                    status.replace('\n',' '),Microbot.pauseAllScripts.get(),diagnosticContext);
            }
        }
    }
    private void pass() {
        if(!active())return;
        long passStarted=System.currentTimeMillis();
        if(passStarted<nextRecoveryAt)return;
        if(!AutomationInteractionCoordinator.tryAcquire(AutomationInteractionCoordinator.HIGH_LEVEL_ALCHEMY,
                passStarted,INTERACTION_LEASE_MS)){
            status="Yielding UI to F2P Flipper";
            return;
        }
        try {
            Snapshot s=snapshot();if(!s.loggedIn){status="Waiting for login";return;}
            if(!active())return;
            if(account==0){
                account=s.account;loadLedger(account);
                offerTracker.restore(config.getConfiguration("highlevelalchemy","offers_"+account));
                savedProfit=config.getConfiguration("highlevelalchemy","purchases_"+account);
                profit.restore(savedProfit);profitInitialized=savedProfit!=null&&!savedProfit.isEmpty();
            }else if(account!=s.account){stopRun();status="Account changed; stopped";return;}
            if (s.world != lastWorld) {
                lastWorld = s.world;
                bankSeen = false;
                caster.reset();
                selectedAlch = -1;
                castingItemId = -1;
                nextService = 0;
            }
            long now=System.currentTimeMillis();
            if (s.days < 0) {
                status = "Waiting for membership status";
                return;
            }
            if (AlchemyPolicy.needsMembersWorld(s.days, s.membersWorld)) {
                hopToMembersWorld(now);
                return;
            }
            if(s.magic<55){status="Requires level 55 Magic";return;}
            if (s.includesMembersItems() && addedMembersItems == null) {
                Map<Integer, AlchemyPolicy.Item> catalogue = prices.requestMembersItems();
                if (catalogue != null) {
                    addedMembersItems = new LinkedHashMap<>(catalogue);
                    addedMembersItems.keySet().removeAll(byId.keySet());
                    byId.putAll(addedMembersItems);
                    s.candidates = byId.values();
                    for (AlchemyPolicy.Item item : addedMembersItems.values())
                        s.ownedValue += item.highAlch * (s.count(s.inventory, item.id)
                            + s.count(s.bank, item.id) + s.count(s.equipment, item.id));
                }
            }
            if (System.currentTimeMillis()-lastDiagnosticAt>=30000) diagnosticContext=diagnosticSnapshot(s,now);
            boolean flipperActive=AutomationInteractionCoordinator.isAutomationActive(
                AutomationInteractionCoordinator.F2P_FLIPPER);
            if(!profitInitialized)for(AlchemyPolicy.Item item:s.candidates)
                profit.seed(item.id,s.count(s.inventory,item.id)+s.count(s.bank,item.id));
            if(!flipperActive)for(Offer offer:s.offers)profit.observe(offer.slot,offer.id,offer.quantity,offer.price,offer.traded,offer.spent,
                offer.state==GrandExchangeOfferState.BUYING||offer.state==GrandExchangeOfferState.BOUGHT||offer.state==GrandExchangeOfferState.CANCELLED_BUY,!profitInitialized);
            profitInitialized=true;saveProfit();
            reconcileOwnedOffers(s);
            if(!flipperActive){recordTradeOffers(s);updatePurchaseComparison(s);}
            Long recordedRunePrice=tradeHistory.latestBuyPrice(account,561,"Nature rune");
            ownedNaturePrice=recordedRunePrice==null?prices.ownedPrice(561,now)
                :(int)Math.min(Integer.MAX_VALUE,recordedRunePrice);
            // Cost discovery must never interrupt the selected spell/item pair.
            if(caster.busy()){alch(s,"Alching inventory",now);return;}
            if(!resolveUnknownCosts(s,now,flipperActive))return;
            if(!flipperActive)for(Offer o:s.offers)if(o.buying()&&byId.containsKey(o.id))cooldown.put(o.id,now+AlchemyPolicy.BUY_LIMIT_WINDOW_MS);
            if(now-lastSave>60000)saveLedger(now);
            if (s.fireStaff == 0 && s.hasUnnotedFireStaff) {
                status = "Equipping Staff of fire";
                closeBank();
                closeGe();
                if (active()) Rs2Inventory.wield(FIRE_STAFF);
                return;
            }
            boolean hasItems=s.candidates.stream().anyMatch(i->s.count(s.inventory,i.id)>0 && canAlch(i, s, now));
            boolean hasRunes=s.count(s.inventory,561)>0;
            boolean inventoryWork=s.fireStaff!=0 && hasItems && hasRunes;
            boolean bankOpen = Rs2Bank.isOpen();
            if(inventoryWork && !bankOpen && !AlchemyPolicy.serviceNeeded(now,nextService,hasItems,hasRunes,false)) {
                alch(s,"Alching inventory",now);return;
            }
            if(!bankSeen && !inventoryWork){
                status="Reading bank supplies";closeGe();
                if(active()&&openBankCounted())bankSeen=true;return;
            }
            if (s.fireStaff == 0 && s.count(s.bank, FIRE_STAFF) > 0) {
                withdrawSupply(s, FIRE_STAFF, "Staff of fire", 1, now);
                return;
            }
            if (s.fireStaff == 0 && s.notedFireStaff != -1) {
                status = "Exchanging a noted Staff of fire at the bank";
                if (bankReady() && now >= nextWithdrawal) {
                    Rs2Bank.depositOne(s.notedFireStaff);
                    nextWithdrawal = System.currentTimeMillis() + 1200;
                }
                return;
            }
            if(!flipperActive)for(Offer o:s.offers) {
                if(o.empty()){
                    collectedProgress.remove(o.slot);
                    AutomationInteractionCoordinator.releaseSlot(
                        AutomationInteractionCoordinator.HIGH_LEVEL_ALCHEMY,o.slot);
                    continue;
                }
                // Only manage offers created by this plugin. Unclaimed/manual offers and
                // flipper offers survive plugin stops and client restarts untouched.
                if(!AutomationInteractionCoordinator.HIGH_LEVEL_ALCHEMY.equals(
                    AutomationInteractionCoordinator.slotOwner(o.slot)))continue;
                if (o.buying() && (o.id == FIRE_STAFF || o.id == 561)
                        && offerTracker.stalled(o.slot, o.traded, now)) {
                    status="Cancelling stalled supply offer to retry at a fresh price";
                    closeBank();
                    if (active()) abortWithoutCollecting(o.slot);
                    return;
                }
                String progress=o.id+":"+o.quantity+":"+o.traded;
                boolean partial=o.buying()&&o.traded>0&&!progress.equals(collectedProgress.get(o.slot));
                if((o.collectable()||partial) && now>=nextCollectionAttempt) {
                    boolean toBank=AlchemyPolicy.collectToBank(s.freeSlots);
                    status="Collecting offer to "+(toBank?"bank":"inventory");closeBank();if(!active())return;
                    nextCollectionAttempt=now+3000;
                    if(collectOffer(o,toBank)) {
                        collectedProgress.put(o.slot,o.id+":"+o.quantity+":"+o.traded);
                        if(toBank)bankSeen=false;
                        nextService=now+60000;
                    }
                    return;
                }
            }
            if (!inventoryWork && AlchemyPolicy.withdrawalSlots(s.freeSlots) > 0) {
                if (s.fireStaff != 0 && !hasRunes && s.count(s.bank, 561) > 0) {
                    withdrawSupply(s, 561, "nature runes", AlchemyPolicy.MAX_CASTS_PER_BUY_LIMIT, now);
                    return;
                }
                if (s.count(s.bank, 995) > 0 && (s.fireStaff != 0 || !s.hasOffer(FIRE_STAFF))) {
                    withdrawSupply(s, 995, "coins", Integer.MAX_VALUE, now);
                    return;
                }
            }
            int naturePrice=prices.price(561,now);
            variables="MEMBERSHIP_DAYS = "+s.days+"\nGE_SLOTS = "+AlchemyPolicy.geSlots(s.days)+"\nOWNED_COINS = "+s.coins()+"\nOWNED_HIGH_ALCH_VALUE = "+s.ownedValue+"\nMAX_CASTS/HR = 1200\nMAX_CASTS/BUY_LIMIT = 4800";
            if (s.fireStaff == 0) {
                if(flipperActive)status="F2P Flipper owns GE offer management; waiting for a Staff of fire";
                else restockStaff(s, now);
                return;
            }
            if(s.count(s.inventory,561)==0){
                if(flipperActive)status="F2P Flipper owns GE offer management; waiting for nature runes";
                else restockRunes(s,naturePrice,now);
                return;
            }
            if (withdrawAlchItems(s, now)) return;
            if(flipperActive){
                if(hasItems&&s.count(s.inventory,561)>0){
                    alch(s,"Alching while F2P Flipper manages GE offers",now);
                }else{
                    status="F2P Flipper owns GE offer management; waiting for alch items or nature runes";
                }
                return;
            }
            if (naturePrice == 0) {
                if (hasItems) alch(s, "Alching owned items", now);
                else status = "Waiting for a fresh nature-rune price";
                return;
            }
            AlchemyPolicy.Purchase choice=AlchemyPolicy.select(s.candidates,id->prices.price(id,now),
                id->!s.hasOffer(id) && cooldown.getOrDefault(id,0L)<=now
                    && Microbot.getClientThread().invoke((java.util.function.Supplier<Boolean>)
                        ()->s.includesMembersItems()||!client.getItemDefinition(id).isMembers()),s.count(s.inventory,995),naturePrice);
            if(choice!=null){
                AlchemyPolicy.Item best=choice.item;int bestPrice=choice.price;
                variables+="\n\nTARGET = "+best.name+"\nPROFIT_PER_ITEM = "+choice.profitPerItem+"\nBUY_LIMIT_HIGH_ALCH_VALUE = "+best.buyLimitHighAlchValue()+"\nBUY_LIMIT_LIVE_PRICE = "+best.buyLimitLivePrice(bestPrice)+"\nHIGH_ALCH_VALUE_BUY_LIMIT_DIFFERENCE = HAVBLD = "+best.havbld(bestPrice);
                int quantity=AlchemyPolicy.quantity(best,bestPrice,s.count(s.inventory,995),s.ownedValue);
                if(quantity==best.buyLimit&&s.occupied()<AlchemyPolicy.geSlots(s.days)&&now>=nextBuy){
                    status="Buying "+quantity+" × "+best.name+" @ "+bestPrice;
                    closeBank();if(!active())return;
                    // Reserve before invoking UI; ambiguous timeouts must not submit duplicate offers.
                    cooldown.put(best.id,now+AlchemyPolicy.BUY_LIMIT_WINDOW_MS);saveLedger(now);nextBuy=now+15000;
                    buyCounted(best.id,best.name,bestPrice,quantity);nextService=now+60000;return;
                }
            }
            nextService=now+60000;
            alch(s,"Alching owned items",now);
        } catch(OverlapAlcher.RecoverableFailure failure) {
            if(!running||Thread.currentThread().isInterrupted())return;
            try{caster.recover(client);}catch(RuntimeException recoveryError){failure.addSuppressed(recoveryError);}
            AlchemyFailureGuard.Decision decision=failureGuard.failure(System.currentTimeMillis());
            if(decision.stop){
                status="Stopped after "+decision.failures+" UI recoveries in two minutes: "+failure.getMessage();running=false;
                LoggerFactory.getLogger(getClass()).warn("HIGH_LEVEL_ALCHEMY fail-safe stopped repeated UI recovery loop",failure);
                logCounters("Recovery limit reached");worker.shutdown();priceWorker.shutdownNow();
                AutomationInteractionCoordinator.setAutomationActive(AutomationInteractionCoordinator.HIGH_LEVEL_ALCHEMY,false);
            }else{
                nextRecoveryAt=System.currentTimeMillis()+decision.backoffMillis;
                status="Recovering UI ("+decision.failures+"/"+AlchemyFailureGuard.TOLERATED_FAILURES+") in "
                    +(decision.backoffMillis/1000.0)+"s: "+failure.getMessage();
                LoggerFactory.getLogger(getClass()).warn("HIGH_LEVEL_ALCHEMY recoverable UI failure {}/{}; retrying after {} ms: {}",
                    decision.failures,AlchemyFailureGuard.TOLERATED_FAILURES,decision.backoffMillis,failure.getMessage());
            }
        } catch(Exception e) {
            if(!running||Thread.currentThread().isInterrupted())return;
            status="Stopped: "+e.getClass().getSimpleName()+": "+e.getMessage();running=false;
            LoggerFactory.getLogger(getClass()).warn("HIGH_LEVEL_ALCHEMY stopped",e);
            logCounters("Run failed");worker.shutdown();priceWorker.shutdownNow();
            AutomationInteractionCoordinator.setAutomationActive(AutomationInteractionCoordinator.HIGH_LEVEL_ALCHEMY,false);
        } finally {
            if(active()&&caster.interactionPending())
                AutomationInteractionCoordinator.renew(AutomationInteractionCoordinator.HIGH_LEVEL_ALCHEMY,
                    System.currentTimeMillis(),INTERACTION_LEASE_MS);
            else AutomationInteractionCoordinator.release(AutomationInteractionCoordinator.HIGH_LEVEL_ALCHEMY);
        }
    }
    private String diagnosticSnapshot(Snapshot s,long now) {
        int naturePrice=prices.price(561,now), eligible=0, missingPrice=0, unprofitable=0;
        List<String> blocked=new ArrayList<>();
        for(AlchemyPolicy.Item item:s.candidates) {
            long inventory=s.count(s.inventory,item.id), bank=s.count(s.bank,item.id);
            if(inventory+bank==0)continue;
            if(canAlch(item,s,now)){eligible++;continue;}
            int price=prices.price(item.id,now);
            String reason=price==0||naturePrice==0?"missing fresh price":"not profitable";
            if(price==0||naturePrice==0)missingPrice++;else unprofitable++;
            if(blocked.size()<10)blocked.add(item.name+"(inventory="+inventory+",bank="+bank
                +",price="+price+",alch="+item.highAlch+",reason="+reason+")");
        }
        return "world="+s.world+", membersWorld="+s.membersWorld+", membershipDays="+s.days
            +", magic="+s.magic+", fireStaff="+(s.fireStaff!=0)+", natureRunes="+s.count(s.inventory,561)
            +", naturePrice="+naturePrice+", freeSlots="+s.freeSlots+", bankSeen="+bankSeen
            +", eligibleOwnedTypes="+eligible+", missingPriceTypes="+missingPrice
            +", unprofitableTypes="+unprofitable+", blocked="+blocked
            +", offers="+s.occupied()+"/"+AlchemyPolicy.geSlots(s.days)
            +", flipperActive="+AutomationInteractionCoordinator.isAutomationActive(AutomationInteractionCoordinator.F2P_FLIPPER);
    }
    private void withdrawSupply(Snapshot s, int id, String name, int amount, long now) {
        status = "Withdrawing " + name;
        if (AlchemyPolicy.withdrawalSlots(s.freeSlots) == 0) {
            status = "Free inventory space for " + name + " (25 occupied slots or fewer)";
            return;
        }
        if (!bankReady() || now < nextWithdrawal || !Rs2Bank.setWithdrawAsItem() || !active()) return;
        nextWithdrawal = System.currentTimeMillis() + 1200;
        if (amount == 1) Rs2Bank.withdrawOne(id);
        else if (s.count(s.bank, id) <= amount) Rs2Bank.withdrawAll(id);
        else Rs2Bank.withdrawX(id, amount);
    }
    private boolean bankReady() {
        if (Rs2Bank.isOpen()) return active();
        closeGe();
        if (active() && openBankCounted()) bankSeen = true;
        // Read a new snapshot after opening the bank.
        return false;
    }
    private boolean withdrawAlchItems(Snapshot s, long now) {
        int slots = AlchemyPolicy.withdrawalSlots(s.freeSlots);
        if (slots == 0) return false;
        if (Rs2Bank.isOpen() && now < nextWithdrawal) return true;
        List<AlchemyPolicy.Item> available = new ArrayList<>();
        List<AlchemyPolicy.Item> notable = new ArrayList<>();
        for (AlchemyPolicy.Item item : s.candidates) {
            if (s.count(s.bank, item.id) == 0 || !canAlch(item, s, now)
                    || withdrawalRetry.getOrDefault(item.id, 0L) > now) continue;
            available.add(item);
            if (s.notableBankItems.contains(item.id)) notable.add(item);
        }
        AlchemyPolicy.Item target = AlchemyPolicy.selectAlch(notable.isEmpty() ? available : notable, s.bank,
            selectionRandom.nextBoolean(), selectionRandom);
        if (target == null) return false;
        status = "Withdrawing " + target.name;
        if (!bankReady()) return true;
        boolean note = s.notableBankItems.contains(target.id);
        withdrawalRetry.put(target.id, now + 5000);
        if (Rs2Bank.setWithdrawAs(note) && active()) {
            if (note || s.count(s.bank, target.id) <= slots) Rs2Bank.withdrawAll(target.id);
            else Rs2Bank.withdrawX(target.id, slots);
            nextWithdrawal = System.currentTimeMillis() + 1200;
        }
        return true;
    }
    private void restockStaff(Snapshot s, long now) {
        if (s.hasOffer(FIRE_STAFF)) {
            status = "Waiting for Staff of fire offer";
            return;
        }
        int price = prices.price(FIRE_STAFF, now);
        if (price == 0) {
            status = "Waiting for a fresh Staff of fire price";
            return;
        }
        if (s.count(s.inventory, 995) < price) {
            status = "Need " + price + " inventory coins for Staff of fire";
            return;
        }
        if (s.occupied() >= AlchemyPolicy.geSlots(s.days)) {
            status = "Waiting for a free GE slot for Staff of fire";
            return;
        }
        if (now < nextBuy) return;
        status = "Buying 1 Staff of fire @ " + price;
        closeBank();
        if (!active()) return;
        nextBuy = now + 15000;
        buyCounted(FIRE_STAFF, "Staff of fire", price, 1);
    }
    private void restockRunes(Snapshot s,int price,long now) {
        if(s.hasOffer(561)){status="Waiting for nature-rune offer";return;}
        if(price<=0){status="Waiting for a fresh nature-rune price";return;}
        closeBank();if(!active())return;
        if(s.occupied()>=AlchemyPolicy.geSlots(s.days)) {
            // Release the least valuable pending buy; never cancel the nature-rune offer itself.
            Offer cancel=s.offers.stream().filter(Offer::buying).filter(o->o.id!=561)
                .filter(o->AutomationInteractionCoordinator.HIGH_LEVEL_ALCHEMY.equals(
                    AutomationInteractionCoordinator.slotOwner(o.slot)))
                .min(Comparator.comparingLong(o->{AlchemyPolicy.Item i=byId.get(o.id);int p=prices.price(o.id,now);return i==null?Long.MIN_VALUE:i.havbld(p);})).orElse(null);
            if(cancel==null){status="No cancellable buy offer; collect or free a GE slot";return;}
            status="Cancelling slot "+(cancel.slot+1)+" to buy nature runes";
            abortWithoutCollecting(cancel.slot);return;
        }
        int quantity=(int)Math.min(AlchemyPolicy.MAX_CASTS_PER_BUY_LIMIT,s.count(s.inventory,995)/price);
        if(quantity<=0){status="No coins for nature runes";return;}
        if(now<nextBuy)return;status="Buying "+quantity+" nature runes @ "+price;nextBuy=now+15000;
        if(active())buyCounted(561,"Nature rune",price,quantity);
    }
    private void saveProfit(){
        String current=profit.serialize();
        if(!current.equals(savedProfit)){config.setConfiguration("highlevelalchemy","purchases_"+account,current);savedProfit=current;}
    }
    private void recordAlch(){
        count(RunCounters.Action.ALCHS);
        failureGuard.success();
        AlchemyPolicy.Item item=byId.get(selectedAlch);
        if(item==null)return;
        java.math.BigDecimal earned=profit.alch(item.id,item.highAlch,castingPrice,castingNaturePrice);
        saveProfit();
        if(profitOverlay!=null)profitOverlay.add(earned);
        LoggerFactory.getLogger(getClass()).info("HIGH_LEVEL_ALCHEMY alch profit: item={}, profit={}, total={} gp, unknownCostAlchs={}",
            item.name,earned==null?"cost unknown":AlchemyProfit.gp(earned)+" gp",AlchemyProfit.gp(profit.total()),profit.unknown());
    }
    private void logCounters(String event){LoggerFactory.getLogger(getClass()).info("HIGH_LEVEL_ALCHEMY counters [{}] {}",event,counters.summary());}
    private void count(RunCounters.Action action){counters.increment(action);logCounters(action.name());}
    private boolean openBankCounted() {
        boolean before=Rs2Bank.isOpen();
        if(before)return true;
        boolean result=Rs2Bank.openBank();boolean after=Rs2Bank.isOpen();
        if(RunCounters.opened(before,after)){caster.reset();count(RunCounters.Action.BANK_OPENS);}
        return result&&after;
    }
    private boolean openGeCounted() {
        boolean before=Rs2GrandExchange.isOpen()||Rs2GrandExchange.isOfferScreenOpen();
        if(before)return true;
        boolean result=Rs2GrandExchange.openExchange();
        boolean after=Rs2GrandExchange.isOpen()||Rs2GrandExchange.isOfferScreenOpen();
        if(RunCounters.opened(before,after)){caster.reset();count(RunCounters.Action.GE_OPENS);}
        return result&&after;
    }
    private boolean buyCounted(int id,String name,int price,int quantity) {
        if(!openGeCounted()||!active())return false;
        boolean result=Rs2GrandExchange.buyItem(name,price,quantity);
        int observedSlot=Microbot.getClientThread().invoke((java.util.function.Supplier<Integer>)()->{
            GrandExchangeOffer[] offers=client.getGrandExchangeOffers();
            for(int slot=0;slot<offers.length;slot++){
                GrandExchangeOffer offer=offers[slot];
                if(offer!=null && offer.getItemId()==id && offer.getPrice()==price && offer.getTotalQuantity()==quantity
                        && (offer.getState()==GrandExchangeOfferState.BUYING||offer.getState()==GrandExchangeOfferState.BOUGHT))return slot;
            }
            return -1;
        });
        boolean observed=observedSlot>=0;
        if(observed) {
            AutomationInteractionCoordinator.claimSlot(AutomationInteractionCoordinator.HIGH_LEVEL_ALCHEMY,observedSlot);
            offerTracker.remember(observedSlot,id,quantity,price);
            saveOwnedOffers();
        }
        if(observed)count(RunCounters.Action.BUY_OFFERS);
        else LoggerFactory.getLogger(getClass()).info("HIGH_LEVEL_ALCHEMY buy not confirmed: {} x {} @ {} (helper result {})",quantity,name,price,result);
        return observed;
    }
    private void reconcileOwnedOffers(Snapshot snapshot) {
        for (Offer offer : snapshot.offers) {
            String owner=AutomationInteractionCoordinator.slotOwner(offer.slot);
            if (offer.empty() || !owner.isEmpty() && !owner.equals(AutomationInteractionCoordinator.HIGH_LEVEL_ALCHEMY)) {
                offerTracker.forget(offer.slot);
            } else if (owner.equals(AutomationInteractionCoordinator.HIGH_LEVEL_ALCHEMY)) {
                offerTracker.remember(offer.slot,offer.id,offer.quantity,offer.price);
            } else if (offerTracker.matches(offer.slot,offer.id,offer.quantity,offer.price)) {
                AutomationInteractionCoordinator.claimSlot(AutomationInteractionCoordinator.HIGH_LEVEL_ALCHEMY,offer.slot);
            } else {
                offerTracker.forget(offer.slot);
            }
        }
        saveOwnedOffers();
    }
    private void saveOwnedOffers() {
        String saved=offerTracker.serialize();
        String key="offers_"+account;
        if (!saved.equals(config.getConfiguration("highlevelalchemy",key)))
            config.setConfiguration("highlevelalchemy",key,saved);
    }
    private void closeBank(){if(Rs2Bank.isOpen())Rs2Bank.closeBank();}
    private void closeGe(){if(Rs2GrandExchange.isOpen()||Rs2GrandExchange.isOfferScreenOpen())Rs2GrandExchange.closeExchange();}
    private boolean collectOffer(Offer offer,boolean toBank) {
        if(!openGeCounted()||!active())return false;
        GrandExchangeSlots slot=Rs2GrandExchange.getSlotFromIndex(offer.slot);
        if(slot==null)return false;
        String name=Microbot.getClientThread().invoke(()->{
            ItemComposition definition=client.getItemDefinition(offer.id);
            return definition==null?String.valueOf(offer.id):definition.getName();
        });
        GrandExchangeRequest request=GrandExchangeRequest.builder()
            .action(GrandExchangeAction.COLLECT).slot(slot).itemName(name)
            .exact(true).toBank(toBank).build();
        boolean collected=Rs2GrandExchange.processOffer(request);
        if(collected)count(RunCounters.Action.COLLECTIONS);
        return collected;
    }
    private void abortWithoutCollecting(int slot) {
        if(!openGeCounted()||!active())return;
        // Same slot/action mapping as Microbot 2.6.34's abort helper, without collectAll's depositAll side effect.
        Rectangle bounds=Microbot.getClientThread().invoke(()->{
            net.runelite.api.widgets.Widget w=client.getWidget(net.runelite.api.gameval.InterfaceID.GE_OFFERS,7+slot);
            return w==null?null:new Rectangle(w.getBounds());
        });
        if(bounds==null||bounds.isEmpty())return;
        Microbot.doInvoke(new net.runelite.client.plugins.microbot.util.menu.NewMenuEntry()
            .option("Abort offer").target("").identifier(2).type(MenuAction.CC_OP).param0(2)
            .param1((net.runelite.api.gameval.InterfaceID.GE_OFFERS<<16)|(7+slot)).itemId(-1).forceLeftClick(false),bounds);
        nextCollectionAttempt=0;
    }
    private void recordTradeOffers(Snapshot snapshot) {
        List<GeTradeHistoryDatabase.OfferRecord> records=new ArrayList<>();
        for(Offer offer:snapshot.offers){
            boolean complete=offer.collectable()&&offer.traded>0;
            if(!complete){records.add(new GeTradeHistoryDatabase.OfferRecord(offer.slot,offer.id,"","",0,0,true));continue;}
            boolean sell=offer.state==GrandExchangeOfferState.SOLD||offer.state==GrandExchangeOfferState.CANCELLED_SELL;
            String name=offer.name;
            long each=offer.spent>0?Math.max(1,offer.spent/Math.max(1,offer.traded)):offer.price;
            records.add(new GeTradeHistoryDatabase.OfferRecord(offer.slot,offer.id,name,sell?"SELL":"BUY",offer.traded,each,false));
        }
        try{int added=tradeHistory.recordOffers(account,records);if(added>0)LoggerFactory.getLogger(getClass()).info("HIGH_LEVEL_ALCHEMY saved {} completed GE offers",added);}
        catch(RuntimeException error){LoggerFactory.getLogger(getClass()).warn("HIGH_LEVEL_ALCHEMY could not persist live GE offers",error);}
    }
    private void updatePurchaseComparison(Snapshot snapshot) {
        for (Offer offer : snapshot.offers) {
            if (offer.empty()) {
                purchaseComparisonProgress.remove(offer.slot);
                continue;
            }
            AlchemyPolicy.Item item = byId.get(offer.id);
            boolean buy = offer.buying() || offer.state == GrandExchangeOfferState.BOUGHT
                || offer.state == GrandExchangeOfferState.CANCELLED_BUY;
            if (!buy || item == null || offer.traded <= 0) continue;
            long total = offer.spent > 0 ? offer.spent : Math.multiplyExact(offer.traded, offer.price);
            String progress = offer.id + ":" + offer.traded + ":" + total;
            if (progress.equals(purchaseComparisonProgress.put(offer.slot, progress))) continue;
            lastPurchaseComparison = PurchaseComparison.format(item.name, offer.traded, total, offer.price, item.highAlch);
        }
    }
    private static final class HistoryView {final boolean visible;final List<GeHistoryParser.Entry> rows;HistoryView(boolean visible,List<GeHistoryParser.Entry> rows){this.visible=visible;this.rows=rows;}}
    private HistoryView historyView(){
        return Microbot.getClientThread().invoke((java.util.function.Supplier<HistoryView>)(()->{
            net.runelite.api.widgets.Widget list=client.getWidget(net.runelite.api.gameval.InterfaceID.GeHistory.LIST);
            boolean visible=list!=null&&!list.isHidden();return new HistoryView(visible,visible?GeHistoryParser.capture(client):Collections.emptyList());
        }));
    }
    private boolean resolveUnknownCosts(Snapshot snapshot,long now,boolean flipperActive){
        List<AlchemyPolicy.Item> unknown=unknownInventory(snapshot);
        resolveFromDatabase(unknown);
        unknown=unknownInventory(snapshot);
        unknown.removeIf(item->!canAlch(item,snapshot,now));
        if(unknown.isEmpty() && historyLookup!=HistoryLookup.EXITING_HISTORY)return true;
        if(flipperActive){
            historyLookup=HistoryLookup.DONE;fallbackToLivePrices(unknown,now);saveProfit();
            status="Using recorded or live costs while F2P Flipper owns GE offer management";
            return true;
        }
        HistoryView view=historyView();
        if (view.visible && historyLookup == HistoryLookup.EXITING_HISTORY && now >= historyDeadline) {
            Rs2Widget.clickChildWidget(net.runelite.api.gameval.InterfaceID.GeHistory.FRAME,11);
            if (historyView().visible)
                throw new OverlapAlcher.RecoverableFailure("GE history did not close after its deadline");
            fallbackToLivePrices(unknown,now);saveProfit();return true;
        }
        if (historyLookup == HistoryLookup.EXITING_HISTORY && !view.visible) {
            historyLookup=HistoryLookup.DONE;
            fallbackToLivePrices(unknown,now);saveProfit();return true;
        }
        if(view.visible){
            try{int added=tradeHistory.recordHistory(account,view.rows,this::itemId);if(added>0)LoggerFactory.getLogger(getClass()).info("HIGH_LEVEL_ALCHEMY saved {} GE history rows",added);}
            catch(RuntimeException error){LoggerFactory.getLogger(getClass()).warn("HIGH_LEVEL_ALCHEMY could not persist GE history widget",error);}
        }
        if(view.visible){
            if (historyLookup != HistoryLookup.EXITING_HISTORY) historyDeadline=now+5000;
            historyLookup=HistoryLookup.EXITING_HISTORY;fallbackToLivePrices(unknown,now);saveProfit();
            Rs2Widget.clickWidget(net.runelite.api.gameval.InterfaceID.GeHistory.EXCHANGE);
            status="GE history checked · using recorded costs or live-price fallback";return false;
        }
        if(historyLookup==HistoryLookup.DONE){fallbackToLivePrices(unknown,now);saveProfit();return true;}
        if((historyLookup==HistoryLookup.OPENING_EXCHANGE||historyLookup==HistoryLookup.OPENING_HISTORY)&&now>=historyDeadline){
            historyLookup=HistoryLookup.DONE;fallbackToLivePrices(unknown,now);saveProfit();status="GE history unavailable · using live item prices";return true;
        }
        if(historyLookup==HistoryLookup.IDLE){
            historyDeadline=now+5000;
            if(Rs2GrandExchange.isOpen()){
                boolean clicked=Rs2Widget.clickWidget(net.runelite.api.gameval.InterfaceID.GeOffers.HISTORY);
                historyLookup=clicked?HistoryLookup.OPENING_HISTORY:HistoryLookup.DONE;
                if(!clicked){fallbackToLivePrices(unknown,now);saveProfit();return true;}
                status="Checking GE history once for actual item costs";return false;
            }
            boolean opened=openGeCounted();historyLookup=opened?HistoryLookup.OPENING_EXCHANGE:HistoryLookup.DONE;
            if(!opened){fallbackToLivePrices(unknown,now);saveProfit();status="Could not open GE history · using live item prices";return true;}
            status="Opening GE once to check actual item costs";return false;
        }
        if(historyLookup==HistoryLookup.OPENING_EXCHANGE&&Rs2GrandExchange.isOpen()){
            boolean clicked=Rs2Widget.clickWidget(net.runelite.api.gameval.InterfaceID.GeOffers.HISTORY);
            historyLookup=clicked?HistoryLookup.OPENING_HISTORY:HistoryLookup.DONE;
            if(!clicked){fallbackToLivePrices(unknown,now);saveProfit();return true;}
            status="Checking GE history once for actual item costs";
        }
        return false;
    }
    private List<AlchemyPolicy.Item> unknownInventory(Snapshot snapshot){
        List<AlchemyPolicy.Item> result=new ArrayList<>();for(AlchemyPolicy.Item item:snapshot.candidates)if(snapshot.count(snapshot.inventory,item.id)>0&&!profit.hasKnownCost(item.id))result.add(item);return result;
    }
    private void resolveFromDatabase(List<AlchemyPolicy.Item> unknown){for(AlchemyPolicy.Item item:unknown){Long price=tradeHistory.latestBuyPrice(account,item.id,item.name);if(price!=null&&profit.resolveUnknownCost(item.id,price))LoggerFactory.getLogger(getClass()).info("HIGH_LEVEL_ALCHEMY restored actual GE buy cost: item={}, price={}",item.name,price);}}
    private void fallbackToLivePrices(List<AlchemyPolicy.Item> unknown,long now){for(AlchemyPolicy.Item item:unknown){int price=prices.ownedPrice(item.id,now);if(price>0&&profit.resolveUnknownCost(item.id,price))LoggerFactory.getLogger(getClass()).info("HIGH_LEVEL_ALCHEMY history cost unavailable; using cached market-price fallback: item={}, price={}",item.name,price);}}
    private int itemId(String name) {
        for (AlchemyPolicy.Item item : byId.values()) if (item.name.equalsIgnoreCase(name)) return item.id;
        // History records retain the item name and can resolve costs by name later.
        // An unrelated history row must never trigger a network search on the action worker.
        return -1;
    }
    private boolean canAlch(AlchemyPolicy.Item item, Snapshot s, long now) {
        if (addedMembersItems == null || !addedMembersItems.containsKey(item.id)) return true;
        return s.includesMembersItems() && AlchemyPolicy.profitableOwned(item,profit.knownCost(item.id),
            prices.ownedPrice(item.id,now),ownedNaturePrice);
    }
    private void alch(Snapshot s,String reason,long now) {
        // Finish the spell/item click pair before considering GE servicing or a new target.
        if(caster.busy()) {status=caster.step(client,castingItemId,now,this::active,this::recordAlch);return;}
        AlchemyPolicy.Item target=byId.get(selectedAlch);
        if(target==null||s.count(s.inventory,target.id)==0||!canAlch(target,s,now)) {
            List<AlchemyPolicy.Item> available = new ArrayList<>();
            for (AlchemyPolicy.Item item : s.candidates) {
                if (s.count(s.inventory, item.id) > 0 && canAlch(item, s, now)) available.add(item);
            }
            smallestMode=selectionRandom.nextBoolean();
            target=AlchemyPolicy.selectAlch(available,s.inventory,smallestMode,selectionRandom);
            selectedAlch=target==null?-1:target.id;caster.reset();
        }
        if (target == null) {status="Waiting for offers / no accessible alchemy items";return;}
        closeBank();closeGe();if(!active())return;
        castingPrice=prices.ownedPrice(target.id,now);
        castingNaturePrice=ownedNaturePrice;
        castingItemId=s.inventoryIds.get(target.id);
        status=reason+" · "+(smallestMode?"small-stack":"high-value")+" pick: "+target.name+"\n"
            +caster.step(client,castingItemId,now,this::active,this::recordAlch);
    }
}
