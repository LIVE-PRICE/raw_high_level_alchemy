package net.runelite.client.plugins.microbot.highlevelalchemy;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Append-only local GE trade database with a global ledger and one ledger per account hash. */
final class GeTradeHistoryDatabase {
    static final class OfferRecord {
        final int slot,itemId;final String itemName,operation;final long quantity,priceEach;final boolean empty;
        OfferRecord(int slot,int itemId,String itemName,String operation,long quantity,long priceEach,boolean empty){this.slot=slot;this.itemId=itemId;this.itemName=itemName;this.operation=operation;this.quantity=quantity;this.priceEach=priceEach;this.empty=empty;}
        String signature(){return operation+"|"+itemId+"|"+quantity+"|"+priceEach;}
    }
    private final Path root,global,accounts,state;
    GeTradeHistoryDatabase(){this(Paths.get(System.getProperty("user.home"),".runelite","high-level-alchemy","ge-history"));}
    GeTradeHistoryDatabase(Path root){this.root=root;global=root.resolve("all-accounts.jsonl");accounts=root.resolve("accounts");state=root.resolve("state");}

    synchronized int recordHistory(long account,List<GeHistoryParser.Entry> rows,java.util.function.Function<String,Integer> ids){
        try{
            List<GeHistoryParser.Entry> previous=readSnapshot(account);int overlap=overlap(rows,previous),added=Math.max(0,rows.size()-overlap);
            for(int index=added-1;index>=0;index--){GeHistoryParser.Entry row=rows.get(index);append(account,row.operation,ids.apply(row.itemName),row.itemName,row.quantity,row.priceEach,"GE_HISTORY");}
            writeSnapshot(account,rows);return added;
        }catch(IOException error){throw new IllegalStateException("Could not save GE history database: "+error.getMessage(),error);}
    }
    synchronized int recordOffers(long account,List<OfferRecord> offers){
        try{
            JsonObject checkpoints=readObject(state.resolve("offers-"+account+".json"));int added=0;
            for(OfferRecord offer:offers){String key=String.valueOf(offer.slot);if(offer.empty){checkpoints.remove(key);continue;}String signature=offer.signature();if(!signature.equals(string(checkpoints,key))){append(account,offer.operation,offer.itemId,offer.itemName,offer.quantity,offer.priceEach,"LIVE_OFFER");checkpoints.addProperty(key,signature);added++;}}
            writeObject(state.resolve("offers-"+account+".json"),checkpoints);return added;
        }catch(IOException error){throw new IllegalStateException("Could not save live GE offers: "+error.getMessage(),error);}
    }
    synchronized Long latestBuyPrice(long account,int itemId,String itemName){
        Path file=accountFile(account);if(!Files.exists(file))return null;Long latest=null;
        try(BufferedReader reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)){String line;while((line=reader.readLine())!=null)try{JsonObject event=new JsonParser().parse(line).getAsJsonObject();if(!"BUY".equals(string(event,"operation")))continue;boolean idMatch=itemId>0&&event.has("itemId")&&event.get("itemId").getAsInt()==itemId;boolean nameMatch=itemName!=null&&itemName.equalsIgnoreCase(string(event,"itemName"));if(idMatch||nameMatch)latest=event.get("priceEach").getAsLong();}catch(RuntimeException ignored){}}
        catch(IOException error){throw new IllegalStateException("Could not read GE history database: "+error.getMessage(),error);}return latest;
    }
    Path globalFile(){return global;}Path accountFile(long account){return accounts.resolve(account+".jsonl");}

    private void append(long account,String operation,int itemId,String itemName,long quantity,long priceEach,String source)throws IOException{
        Files.createDirectories(accounts);JsonObject event=new JsonObject();event.addProperty("recordedAt",System.currentTimeMillis());event.addProperty("accountHash",account);event.addProperty("operation",operation);event.addProperty("itemId",itemId);event.addProperty("itemName",itemName);event.addProperty("quantity",quantity);event.addProperty("priceEach",priceEach);event.addProperty("total",Math.multiplyExact(quantity,priceEach));event.addProperty("source",source);String line=new GsonBuilder().disableHtmlEscaping().create().toJson(event)+System.lineSeparator();
        Files.writeString(global,line,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);Files.writeString(accountFile(account),line,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    private List<GeHistoryParser.Entry> readSnapshot(long account)throws IOException{Path file=state.resolve("history-"+account+".json");if(!Files.exists(file))return Collections.emptyList();JsonArray array=new JsonParser().parse(Files.readString(file)).getAsJsonArray();List<GeHistoryParser.Entry> rows=new ArrayList<>();for(JsonElement value:array){JsonObject row=value.getAsJsonObject();rows.add(new GeHistoryParser.Entry(string(row,"operation"),string(row,"itemName"),row.get("quantity").getAsLong(),row.get("priceEach").getAsLong()));}return rows;}
    private void writeSnapshot(long account,List<GeHistoryParser.Entry> rows)throws IOException{JsonArray array=new JsonArray();for(GeHistoryParser.Entry row:rows){JsonObject value=new JsonObject();value.addProperty("operation",row.operation);value.addProperty("itemName",row.itemName);value.addProperty("quantity",row.quantity);value.addProperty("priceEach",row.priceEach);array.add(value);}write(state.resolve("history-"+account+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(array));}
    private static int overlap(List<GeHistoryParser.Entry> current,List<GeHistoryParser.Entry> previous){int max=Math.min(current.size(),previous.size());for(int count=max;count>0;count--){boolean same=true;for(int i=0;i<count;i++)if(!current.get(current.size()-count+i).equals(previous.get(i))){same=false;break;}if(same)return count;}return 0;}
    private static JsonObject readObject(Path file)throws IOException{return Files.exists(file)?new JsonParser().parse(Files.readString(file)).getAsJsonObject():new JsonObject();}
    private static void writeObject(Path file,JsonObject object)throws IOException{write(file,new GsonBuilder().setPrettyPrinting().create().toJson(object));}
    private static void write(Path file,String value)throws IOException{Files.createDirectories(file.getParent());Path temporary=file.resolveSibling(file.getFileName()+".tmp");Files.writeString(temporary,value,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);try{Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException ignored){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}}
    private static String string(JsonObject object,String key){return object.has(key)?object.get(key).getAsString():null;}
}
