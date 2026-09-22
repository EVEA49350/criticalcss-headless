package com.evea.bmsterminal;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

public class MainActivity extends Activity {
    private static final int REQ=1001;
    private static final UUID SVC=UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB");
    private static final UUID CH1=UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB");
    private static final UUID CH2=UUID.fromString("0000FFE2-0000-1000-8000-00805F9B34FB");
    private static final UUID CCCD=UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;

    private TextView status, terminal;
    private ScrollView terminalScroll;
    private ArrayAdapter<String> listAdapter;
    private final ArrayList<String> rows=new ArrayList<>();
    private final ArrayList<BluetoothDevice> found=new ArrayList<>();
    private final Map<String,Integer> byAddr=new HashMap<>();
    private EditText command;
    private Button scanBtn, disconnectBtn;
    private boolean connected=false, scanning=false;
    private final Handler handler=new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        buildUi();
        BluetoothManager m=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        adapter=m==null?null:m.getAdapter();
        if(adapter==null){ setStatus("Bluetooth non disponible",false); scanBtn.setEnabled(false); return; }
        requestOrScan();
    }

    private int dp(int v){ return Math.round(v*getResources().getDisplayMetrics().density); }
    private Button button(String t){ Button b=new Button(this); b.setText(t); b.setAllCaps(false); return b; }

    private void buildUi(){
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(10),dp(10),dp(10),dp(10));

        TextView title=new TextView(this); title.setText("EVEA BMS Terminal"); title.setTextSize(22); title.setTextColor(Color.BLACK);
        root.addView(title);

        status=new TextView(this); status.setText("Initialisation…"); status.setPadding(0,dp(4),0,dp(8)); root.addView(status);

        LinearLayout top=new LinearLayout(this);
        scanBtn=button("SCAN"); scanBtn.setOnClickListener(v->requestOrScan());
        disconnectBtn=button("DÉCONNECTER"); disconnectBtn.setEnabled(false); disconnectBtn.setOnClickListener(v->disconnect());
        Button clear=button("EFFACER"); clear.setOnClickListener(v->terminal.setText(""));
        top.addView(scanBtn,new LinearLayout.LayoutParams(0,dp(48),1));
        top.addView(disconnectBtn,new LinearLayout.LayoutParams(0,dp(48),1.4f));
        top.addView(clear,new LinearLayout.LayoutParams(0,dp(48),1));
        root.addView(top);

        listAdapter=new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,rows);
        ListView list=new ListView(this); list.setAdapter(listAdapter);
        list.setOnItemClickListener((p,v,pos,id)->{ stopScan(); connect(found.get(pos)); });
        root.addView(list,new LinearLayout.LayoutParams(-1,dp(150)));

        TextView lab=new TextView(this); lab.setText("Terminal BMS"); lab.setTextSize(15); lab.setPadding(0,dp(6),0,dp(4)); root.addView(lab);

        terminal=new TextView(this); terminal.setTypeface(android.graphics.Typeface.MONOSPACE); terminal.setTextSize(13);
        terminal.setTextColor(Color.rgb(225,240,225)); terminal.setBackgroundColor(Color.rgb(20,24,20)); terminal.setPadding(dp(8),dp(8),dp(8),dp(8)); terminal.setTextIsSelectable(true);
        terminalScroll=new ScrollView(this); terminalScroll.addView(terminal);
        root.addView(terminalScroll,new LinearLayout.LayoutParams(-1,0,1));

        GridLayout grid=new GridLayout(this); grid.setColumnCount(4); grid.setUseDefaultMargins(true);
        for(String c:new String[]{"D0","D1","D2","D3","D4","D5","D6","D7","LB","++","H?","RT"}){
            Button b=button(c);
            if(c.equals("RT")) b.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Reset du BMS").setMessage("Envoyer RT au Master ?").setNegativeButton("Annuler",null).setPositiveButton("RESET",(d,w)->send("RT")).show());
            else b.setOnClickListener(v->send(c));
            GridLayout.LayoutParams gp=new GridLayout.LayoutParams(); gp.width=0; gp.height=dp(44); gp.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f); b.setLayoutParams(gp); grid.addView(b);
        }
        root.addView(grid);

        LinearLayout bottom=new LinearLayout(this);
        command=new EditText(this); command.setHint("Commande"); command.setSingleLine(true);
        Button send=button("ENVOYER"); send.setOnClickListener(v->{ String s=command.getText().toString().trim(); if(!s.isEmpty()){ send(s); command.setText(""); }});
        bottom.addView(command,new LinearLayout.LayoutParams(0,dp(50),1));
        bottom.addView(send,new LinearLayout.LayoutParams(dp(110),dp(50)));
        root.addView(bottom);
        setContentView(root);
    }

    private boolean perms(){
        if(Build.VERSION.SDK_INT>=31) return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }
    private void requestOrScan(){
        if(!perms()){
            if(Build.VERSION.SDK_INT>=31) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT},REQ);
            else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},REQ);
        } else startScan();
    }
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){ super.onRequestPermissionsResult(r,p,g); if(r==REQ && perms()) startScan(); }

    private void startScan(){
        if(!adapter.isEnabled()){ setStatus("Active le Bluetooth du téléphone",false); return; }
        scanner=adapter.getBluetoothLeScanner(); if(scanner==null){setStatus("Scanner BLE indisponible",false);return;}
        rows.clear(); found.clear(); byAddr.clear(); listAdapter.notifyDataSetChanged();
        scanning=true; scanBtn.setText("SCAN…"); setStatus("Recherche BLE…",true);
        scanner.startScan(scanCb); handler.postDelayed(this::stopScan,12000);
    }
    private void stopScan(){
        if(!scanning)return; scanning=false; scanBtn.setText("SCAN");
        try{ if(scanner!=null && perms()) scanner.stopScan(scanCb); }catch(Exception ignored){}
        if(!connected) setStatus(rows.isEmpty()?"Aucun périphérique trouvé":"Sélectionne le BMS",true);
    }

    private final ScanCallback scanCb=new ScanCallback(){
        @Override public void onScanResult(int t,ScanResult r){
            BluetoothDevice d=r.getDevice(); if(d==null)return;
            runOnUiThread(()->{
                String a=d.getAddress(), n;
                try{ n=d.getName(); }catch(Exception e){n=null;}
                if(n==null||n.isEmpty()) n="Périphérique sans nom";
                String row=(n.equalsIgnoreCase("EVEA-BMS-MASTER")?"★ ":"")+n+"\n"+a+"    RSSI "+r.getRssi()+" dBm";
                Integer idx=byAddr.get(a);
                if(idx==null){ byAddr.put(a,rows.size()); rows.add(row); found.add(d); }
                else rows.set(idx,row);
                sortEveaFirst(); listAdapter.notifyDataSetChanged();
            });
        }
        @Override public void onScanFailed(int e){ runOnUiThread(()->setStatus("Erreur scan BLE : "+e,false)); }
    };

    private void sortEveaFirst(){
        for(int i=0;i<rows.size();i++) if(rows.get(i).startsWith("★ ")){ if(i>0){ String rs=rows.remove(i); BluetoothDevice d=found.remove(i); rows.add(0,rs); found.add(0,d); rebuildIndex(); } break; }
    }
    private void rebuildIndex(){ byAddr.clear(); for(int i=0;i<found.size();i++)byAddr.put(found.get(i).getAddress(),i); }

    private void connect(BluetoothDevice d){
        disconnect(); append("\n[APP] Connexion…\n"); setStatus("Connexion…",true);
        try{ gatt=d.connectGatt(this,false,gattCb,BluetoothDevice.TRANSPORT_LE); }catch(Exception e){ setStatus("Connexion impossible",false); }
    }

    private final BluetoothGattCallback gattCb=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt g,int st,int ns){
            if(ns==BluetoothProfile.STATE_CONNECTED){
                connected=true; runOnUiThread(()->{disconnectBtn.setEnabled(true);setStatus("Connecté — découverte des services…",true);append("[APP] Connecté\n");});
                try{g.discoverServices();}catch(Exception ignored){}
            }else if(ns==BluetoothProfile.STATE_DISCONNECTED){
                connected=false; writeChar=null; runOnUiThread(()->{disconnectBtn.setEnabled(false);setStatus("Déconnecté",false);append("[APP] Déconnecté\n");});
            }
        }
        @Override public void onServicesDiscovered(BluetoothGatt g,int st){
            BluetoothGattService s=g.getService(SVC); if(s==null){runOnUiThread(()->setStatus("Service FFE0 introuvable",false));return;}
            BluetoothGattCharacteristic c1=s.getCharacteristic(CH1), c2=s.getCharacteristic(CH2);
            if(c1==null){runOnUiThread(()->setStatus("FFE1 introuvable",false));return;}
            writeChar=((c1.getProperties()&(BluetoothGattCharacteristic.PROPERTY_WRITE|BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE))!=0)?c1:c2;
            try{
                g.setCharacteristicNotification(c1,true);
                BluetoothGattDescriptor d=c1.getDescriptor(CCCD); if(d==null){runOnUiThread(()->setStatus("CCCD 0x2902 introuvable",false));return;}
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); g.writeDescriptor(d);
            }catch(Exception e){runOnUiThread(()->setStatus("Erreur activation notifications",false));}
        }
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int st){
            runOnUiThread(()->{ if(st==BluetoothGatt.GATT_SUCCESS){setStatus("Connecté à EVEA-BMS-MASTER",true);append("[APP] Notifications FFE1 actives\n");} else setStatus("Erreur notifications : "+st,false);});
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){ byte[] v=c.getValue(); if(v!=null) rx(v); }
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] v){ if(v!=null)rx(v); }
    };

    private void rx(byte[] v){ final String s=new String(v,StandardCharsets.UTF_8); runOnUiThread(()->append(s)); }

    private void send(String c){
        if(!connected||gatt==null||writeChar==null){Toast.makeText(this,"BMS non connecté",Toast.LENGTH_SHORT).show();return;}
        String x=c.trim().toUpperCase(Locale.ROOT); byte[] p=(x+"\r\n").getBytes(StandardCharsets.US_ASCII);
        if(p.length>20){Toast.makeText(this,"Commande trop longue",Toast.LENGTH_SHORT).show();return;}
        try{ writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT); writeChar.setValue(p); if(gatt.writeCharacteristic(writeChar)) append("\n> "+x+"\n"); else Toast.makeText(this,"Échec envoi BLE",Toast.LENGTH_SHORT).show(); }
        catch(Exception e){Toast.makeText(this,"Erreur envoi BLE",Toast.LENGTH_SHORT).show();}
    }

    private void append(String s){
        terminal.append(s);
        if(terminal.length()>120000){ CharSequence t=terminal.getText(); terminal.setText(t.subSequence(t.length()-80000,t.length())); }
        terminalScroll.post(()->terminalScroll.fullScroll(View.FOCUS_DOWN));
    }
    private void setStatus(String s,boolean ok){ status.setText(s); status.setTextColor(ok?Color.rgb(0,110,55):Color.rgb(170,30,30)); }

    private void disconnect(){
        connected=false; writeChar=null;
        if(gatt!=null){ try{if(perms())gatt.disconnect();}catch(Exception ignored){} gatt.close(); gatt=null; }
        if(disconnectBtn!=null)disconnectBtn.setEnabled(false);
    }
    @Override protected void onDestroy(){ handler.removeCallbacksAndMessages(null); stopScan(); disconnect(); super.onDestroy(); }
}