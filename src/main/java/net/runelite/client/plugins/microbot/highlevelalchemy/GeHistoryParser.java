package net.runelite.client.plugins.microbot.highlevelalchemy;

import java.util.*;
import java.util.regex.*;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/** Reads the GE history's dynamic text rows (operation, item/quantity, and unit price). */
final class GeHistoryParser {
    static final class Entry {
        final String operation,itemName;final long quantity,priceEach;
        Entry(String operation,String itemName,long quantity,long priceEach){this.operation=operation;this.itemName=itemName;this.quantity=quantity;this.priceEach=priceEach;}
        String signature(){return operation+"|"+itemName.toLowerCase(Locale.ROOT)+"|"+quantity+"|"+priceEach;}
        @Override public boolean equals(Object value){return value instanceof Entry&&signature().equals(((Entry)value).signature());}
        @Override public int hashCode(){return signature().hashCode();}
    }
    private static final Pattern ITEM=Pattern.compile("^(.*?)(?:\\s+x\\s+([0-9,]+))?$",Pattern.CASE_INSENSITIVE);
    private static final Pattern PRICE=Pattern.compile("^([0-9,]+)\\s+coins?$",Pattern.CASE_INSENSITIVE);

    static List<Entry> capture(Client client){
        Widget list=client.getWidget(InterfaceID.GeHistory.LIST);if(list==null||list.isHidden())return Collections.emptyList();
        Map<Integer,String> text=new TreeMap<>();collect(list,text,Collections.newSetFromMap(new IdentityHashMap<>()));return parse(text);
    }
    private static void collect(Widget widget,Map<Integer,String> text,Set<Widget> seen){
        if(widget==null||!seen.add(widget))return;String value=clean(widget.getText());if(widget.getIndex()>=0&&!value.isEmpty()&&!widget.isHidden())text.put(widget.getIndex(),value);
        for(Widget[] children:Arrays.asList(widget.getDynamicChildren(),widget.getStaticChildren(),widget.getNestedChildren()))if(children!=null)for(Widget child:children)collect(child,text,seen);
    }
    static List<Entry> parse(Map<Integer,String> text){
        List<Entry> result=new ArrayList<>();List<Integer> indices=new ArrayList<>(text.keySet());Collections.sort(indices);
        for(int position=0;position<indices.size();position++){
            int index=indices.get(position);String operation=clean(text.get(index));
            if(!operation.equalsIgnoreCase("Bought:")&&!operation.equalsIgnoreCase("Sold:"))continue;
            String item=text.get(index+1),price=text.get(index+3);if(item==null||price==null)continue;
            Matcher itemMatch=ITEM.matcher(clean(item)),priceMatch=PRICE.matcher(clean(price));if(!itemMatch.matches()||!priceMatch.matches())continue;
            String name=itemMatch.group(1).strip();long quantity=itemMatch.group(2)==null?1:quantity(itemMatch.group(2));long each=quantity(priceMatch.group(1));
            if(!name.isEmpty()&&quantity>0&&each>0)result.add(new Entry(operation.regionMatches(true,0,"Bought",0,6)?"BUY":"SELL",name,quantity,each));
        }
        return result;
    }
    private static long quantity(String value){try{return Long.parseLong(value.replace(",",""));}catch(NumberFormatException error){return -1;}}
    private static String clean(String value){return value==null?"":value.replaceAll("<[^>]*>","").replace("\u00a0"," ").strip();}
    private GeHistoryParser(){}
}
