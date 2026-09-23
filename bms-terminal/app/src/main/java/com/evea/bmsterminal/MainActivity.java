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
    private static final long KEEPALIVE_MS=2000L;

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private BluetoothDevice currentDevice;

    private TextView status, terminal;
    private ScrollView terminalScroll;
    private ArrayAdapter<String> listAdapter;
    private final ArrayList<String> rows=new ArrayList<>();
    private final ArrayList<BluetoothDevice> found=new ArrayList<>();
    private final Map<String,Integer> byAddr=new HashMap<>();
    private EditText command;
    private Button scanBtn, disconnectBtn;

    private boolean connected=false;
    private boolean sessionReady=false;
    private boolean scanning=false;
    private boolean writeInProgress=false;
    private boolean closeAfterBd=false;
    private int reconnectAttempt=0;

    private final Handler handler=new Handler(Looper.getMainLooper());
    private final StringBuilder rxBuffer=new StringBuilder();

    private static class WriteRequest {
        final String command;
        final boolean echo;
        WriteRequest(String command,boolean echo){this.command=command;this.echo=echo;}
    }

    private final ArrayDeque<WriteRequest> writeQueue=new ArrayDeque<>();
    private WriteRequest currentWrite=null;

    private final Runnable keepAliveTask=new Runnable(){
        @Override public void run(){
            if(sessionReady && connected && gatt!=null && writeChar!=null){
                if(!writeInProgress && writeQueue.isEmpty()) enqueueCommandOnMain("BK",false,true);
                handler.postDelayed(this,KEEPALIVE_MS);
            }
        }
    };

    private final Runnable forceDisconnectTask=new Runnable(){
        @Override public void run(){
            if(closeAfterBd){
                closeAfterBd=false;
                closeGattNow(true);
            }
        }
    };

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        buildUi();


        BluetoothManager m=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        adapter=m==null?null:m.getAdapter();
        if(adapter==null){setStatus("Bluetooth non disponible",false);scanBtn.setEnabled(false);return;}
        requestOrScan();
    }

    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private Button button(String t){Button b=new Button(this);b.setText(t);b.setAllCaps(false);return b;}

    private LinearLayout connectionPanel;
    private LinearLayout debugPanel;
    private TextView debugTitle;
    private int activeScreen=0; // 0 = Connexion, 1..7 = Debug correspondant

    private void buildUi(){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10),dp(10),dp(10),dp(10));

        LinearLayout header=new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title=new TextView(this);
        title.setText("EVEA BMS Terminal");
        title.setTextSize(22);
        title.setTextColor(Color.BLACK);
        header.addView(title,new LinearLayout.LayoutParams(0,dp(52),1));

        Button menuButton=button("☰");
        menuButton.setTextSize(25);
        menuButton.setContentDescription("Choisir une page");
        menuButton.setOnClickListener(v->showNavigationMenu(menuButton));
        header.addView(menuButton,new LinearLayout.LayoutParams(dp(60),dp(52)));
        root.addView(header);

        status=new TextView(this);
        status.setText("Initialisation…");
        status.setPadding(0,dp(4),0,dp(8));
        root.addView(status);

        connectionPanel=new LinearLayout(this);
        connectionPanel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top=new LinearLayout(this);
        scanBtn=button("SCAN");
        scanBtn.setOnClickListener(v->requestOrScan());
        disconnectBtn=button("DÉCONNECTER");
        disconnectBtn.setEnabled(false);
        disconnectBtn.setOnClickListener(v->disconnectGracefully());
        Button clear=button("EFFACER");
        clear.setOnClickListener(v->{terminal.setText("");rxBuffer.setLength(0);});
        top.addView(scanBtn,new LinearLayout.LayoutParams(0,dp(48),1));
        top.addView(disconnectBtn,new LinearLayout.LayoutParams(0,dp(48),1.4f));
        top.addView(clear,new LinearLayout.LayoutParams(0,dp(48),1));
        connectionPanel.addView(top);

        listAdapter=new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,rows);
        ListView list=new ListView(this);
        list.setAdapter(listAdapter);
        list.setOnItemClickListener((p,v,pos,id)->{stopScan();beginConnection(found.get(pos));});
        connectionPanel.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        root.addView(connectionPanel,new LinearLayout.LayoutParams(-1,0,1));

        debugPanel=new LinearLayout(this);
        debugPanel.setOrientation(LinearLayout.VERTICAL);
        debugPanel.setVisibility(View.GONE);

        debugTitle=new TextView(this);
        debugTitle.setTextSize(17);
        debugTitle.setPadding(0,dp(4),0,dp(8));
        debugPanel.addView(debugTitle);

        terminal=new TextView(this);
        terminal.setTypeface(android.graphics.Typeface.MONOSPACE);
        terminal.setTextSize(13);
        terminal.setTextColor(Color.rgb(225,240,225));
        terminal.setBackgroundColor(Color.rgb(20,24,20));
        terminal.setPadding(dp(8),dp(8),dp(8),dp(8));
        terminal.setTextIsSelectable(true);
        terminalScroll=new ScrollView(this);
        terminalScroll.addView(terminal);
        debugPanel.addView(terminalScroll,new LinearLayout.LayoutParams(-1,0,1));

        GridLayout grid=new GridLayout(this);
        grid.setColumnCount(4);
        grid.setUseDefaultMargins(true);
        for(String c:new String[]{"LB","++","H?","RT"}){
            Button b=button(c);
            if(c.equals("RT")) b.setOnClickListener(v->new AlertDialog.Builder(this)
                .setTitle("Reset du BMS")
                .setMessage("Envoyer RT au Master ?")
                .setNegativeButton("Annuler",null)
                .setPositiveButton("RESET",(d,w)->send("RT"))
                .show());
            else b.setOnClickListener(v->send(c));
            GridLayout.LayoutParams gp=new GridLayout.LayoutParams();
            gp.width=0;
            gp.height=dp(44);
            gp.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);
            b.setLayoutParams(gp);
            grid.addView(b);
        }
        debugPanel.addView(grid);

        LinearLayout bottom=new LinearLayout(this);
        command=new EditText(this);
        command.setHint("Commande");
        command.setSingleLine(true);
        Button sendButton=button("ENVOYER");
        sendButton.setOnClickListener(v->{
            String s=command.getText().toString().trim();
            if(!s.isEmpty()){send(s);command.setText("");}
        });
        bottom.addView(command,new LinearLayout.LayoutParams(0,dp(50),1));
        bottom.addView(sendButton,new LinearLayout.LayoutParams(dp(110),dp(50)));
        debugPanel.addView(bottom);
        root.addView(debugPanel,new LinearLayout.LayoutParams(-1,0,1));

        setContentView(root);
    }

    private void showNavigationMenu(View anchor){
        PopupMenu menu=new PopupMenu(this,anchor);
        menu.getMenu().add(0,0,0,"Connexion");
        for(int i=1;i<=7;i++)menu.getMenu().add(0,i,i,"Debug "+i);
        menu.setOnMenuItemClickListener(item->{selectScreen(item.getItemId());return true;});
        menu.show();
    }

    private void selectScreen(int screen){
        if(screen==activeScreen)return;

        // Chaque changement de page repart avec un terminal et une trame RX vides.
        terminal.setText("");
        rxBuffer.setLength(0);
        activeScreen=screen;

        boolean connectionScreen=(screen==0);
        connectionPanel.setVisibility(connectionScreen?View.VISIBLE:View.GONE);
        debugPanel.setVisibility(connectionScreen?View.GONE:View.VISIBLE);

        if(connectionScreen){
            // L'affichage Connexion ne doit pas maintenir le debug actif.
            if(sessionReady)enqueueCommandOnMain("D0",false,false);
        }else{
            debugTitle.setText("Debug "+screen);
            // La commande choisie dans le menu remplace les anciens boutons D1-D7.
            if(sessionReady)enqueueCommandOnMain("D"+screen,false,false);
            else Toast.makeText(this,"Connecte d'abord le BMS depuis Connexion",Toast.LENGTH_SHORT).show();
        }
    }

    private boolean perms(){
        if(Build.VERSION.SDK_INT>=31) return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }

    private void requestOrScan(){
        if(!perms()){
            if(Build.VERSION.SDK_INT>=31) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT},REQ);
            else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},REQ);
        }else startScan();
    }

    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==REQ && perms()) startScan();}

    private void startScan(){
        if(!adapter.isEnabled()){setStatus("Active le Bluetooth du téléphone",false);return;}
        scanner=adapter.getBluetoothLeScanner();if(scanner==null){setStatus("Scanner BLE indisponible",false);return;}
        rows.clear();found.clear();byAddr.clear();listAdapter.notifyDataSetChanged();
        scanning=true;scanBtn.setText("SCAN…");setStatus("Recherche BLE…",true);
        scanner.startScan(scanCb);handler.postDelayed(this::stopScan,12000);
    }

    private void stopScan(){
        if(!scanning)return;
        scanning=false;scanBtn.setText("SCAN");
        try{if(scanner!=null && perms())scanner.stopScan(scanCb);}catch(Exception ignored){}
        if(!connected)setStatus(rows.isEmpty()?"Aucun périphérique trouvé":"Sélectionne le BMS",true);
    }

    private final ScanCallback scanCb=new ScanCallback(){
        @Override public void onScanResult(int t,ScanResult r){
            BluetoothDevice d=r.getDevice();if(d==null)return;
            runOnUiThread(()->{
                String a=d.getAddress(),n;
                try{n=d.getName();}catch(Exception e){n=null;}
                if(n==null||n.isEmpty())n="Périphérique sans nom";
                String row=(n.equalsIgnoreCase("EVEA-BMS-MASTER")?"★ ":"")+n+"\n"+a+"    RSSI "+r.getRssi()+" dBm";
                Integer idx=byAddr.get(a);
                if(idx==null){byAddr.put(a,rows.size());rows.add(row);found.add(d);}
                else rows.set(idx,row);
                sortEveaFirst();listAdapter.notifyDataSetChanged();
            });
        }
        @Override public void onScanFailed(int e){runOnUiThread(()->setStatus("Erreur scan BLE : "+e,false));}
    };

    private void sortEveaFirst(){
        for(int i=0;i<rows.size();i++)if(rows.get(i).startsWith("★ ")){if(i>0){String rs=rows.remove(i);BluetoothDevice d=found.remove(i);rows.add(0,rs);found.add(0,d);rebuildIndex();}break;}
    }

    private void rebuildIndex(){byAddr.clear();for(int i=0;i<found.size();i++)byAddr.put(found.get(i).getAddress(),i);}

    private void beginConnection(BluetoothDevice d){
        stopKeepAlive();
        closeGattNow(false);
        currentDevice=d;
        reconnectAttempt=0;
        rxBuffer.setLength(0);
        append("\n[APP] Préparation de la connexion…\n");
        setStatus("Connexion…",true);

        /*
         * Pas de createBond() explicite.
         * On ouvre directement le GATT.
         * Si une association est réellement nécessaire, Android la gère lui-même.
         */
        handler.postDelayed(()->connectGattNow(d),250);
    }

    private void connectGattNow(BluetoothDevice d){
        if(d==null || gatt!=null)return;
        currentDevice=d;
        manualDisconnect=false;
        sessionReady=false;
        writeChar=null;
        setStatus(reconnectAttempt>0?"Reconnexion…":"Connexion…",true);
        append(reconnectAttempt>0?"[APP] Nouvelle tentative de connexion GATT…\n":"[APP] Connexion GATT…\n");
        try{
            gatt=d.connectGatt(this,false,gattCb,BluetoothDevice.TRANSPORT_LE);
            if(gatt==null)setStatus("Connexion impossible",false);
        }catch(Exception e){
            setStatus("Connexion impossible",false);
            append("[APP] Erreur connectGatt\n");
        }
    }

    private boolean manualDisconnect=false;

    private final BluetoothGattCallback gattCb=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt g,int st,int ns){
            if(g!=gatt){try{g.close();}catch(Exception ignored){}return;}

            if(ns==BluetoothProfile.STATE_CONNECTED && st==BluetoothGatt.GATT_SUCCESS){
                connected=true;
                runOnUiThread(()->{
                    disconnectBtn.setEnabled(true);
                    setStatus("Connecté — découverte des services…",true);
                    append("[APP] Connecté\n");
                });
                handler.postDelayed(()->{
                    if(g==gatt && connected){
                        try{if(!g.discoverServices())runOnUiThread(()->setStatus("Échec découverte des services",false));}
                        catch(Exception e){runOnUiThread(()->setStatus("Erreur découverte des services",false));}
                    }
                },250);
                return;
            }

            if(ns==BluetoothProfile.STATE_DISCONNECTED){
                connected=false;
                sessionReady=false;
                stopKeepAlive();
                writeChar=null;
                writeQueue.clear();
                writeInProgress=false;
                currentWrite=null;

                BluetoothDevice retryDevice=currentDevice;
                boolean retry=!manualDisconnect && retryDevice!=null && reconnectAttempt<1;

                try{g.close();}catch(Exception ignored){}
                if(g==gatt)gatt=null;

                if(retry){
                    reconnectAttempt++;
                    runOnUiThread(()->{
                        setStatus("Connexion interrompue — nouvelle tentative…",true);
                        append("[APP] Connexion interrompue, nouvelle tentative automatique\n");
                    });
                    handler.postDelayed(()->connectGattNow(retryDevice),700);
                }else{
                    runOnUiThread(()->{
                        disconnectBtn.setEnabled(false);
                        setStatus("Déconnecté",false);
                        append("[APP] Déconnecté\n");
                    });
                }
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g,int st){
            if(g!=gatt)return;
            if(st!=BluetoothGatt.GATT_SUCCESS){runOnUiThread(()->setStatus("Erreur découverte services : "+st,false));return;}

            BluetoothGattService s=g.getService(SVC);
            if(s==null){runOnUiThread(()->setStatus("Service FFE0 introuvable",false));return;}

            BluetoothGattCharacteristic c1=s.getCharacteristic(CH1),c2=s.getCharacteristic(CH2);
            if(c1==null){runOnUiThread(()->setStatus("FFE1 introuvable",false));return;}

            writeChar=((c1.getProperties()&(BluetoothGattCharacteristic.PROPERTY_WRITE|BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE))!=0)?c1:c2;
            if(writeChar==null){runOnUiThread(()->setStatus("Caractéristique d'écriture introuvable",false));return;}

            try{
                if(!g.setCharacteristicNotification(c1,true)){runOnUiThread(()->setStatus("Activation notifications refusée",false));return;}
                BluetoothGattDescriptor d=c1.getDescriptor(CCCD);
                if(d==null){runOnUiThread(()->setStatus("CCCD 0x2902 introuvable",false));return;}
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                if(!g.writeDescriptor(d))runOnUiThread(()->setStatus("Échec écriture CCCD",false));
            }catch(Exception e){
                runOnUiThread(()->setStatus("Erreur activation notifications",false));
            }
        }

        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int st){
            if(g!=gatt)return;
            runOnUiThread(()->{
                if(st==BluetoothGatt.GATT_SUCCESS){
                    sessionReady=true;
                    reconnectAttempt=0;
                    setStatus("Connecté à EVEA-BMS-MASTER",true);
                    append("[APP] Notifications FFE1 actives\n");
                    enqueueCommandOnMain("BC",false,true);
                    startKeepAlive();
                }else{
                    setStatus("Erreur notifications : "+st,false);
                }
            });
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int st){
            if(g!=gatt)return;
            runOnUiThread(()->finishCurrentWrite(st));
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){byte[] v=c.getValue();if(v!=null)rx(v);}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] v){if(v!=null)rx(v);}
    };

    private void rx(byte[] v){
        final String s=new String(v,StandardCharsets.UTF_8);
        runOnUiThread(()->processRx(s));
    }

    private void processRx(String s_rxChunk){
        rxBuffer.append(s_rxChunk);

        int i_lineEnd;
        while((i_lineEnd=rxBuffer.indexOf("\n"))>=0){
            String s_line=rxBuffer.substring(0,i_lineEnd).replace("\r","").trim();
            rxBuffer.delete(0,i_lineEnd+1);
            if(!s_line.isEmpty()) processProtocolLine(s_line);
        }

        /*
         * Sécurité si un flux sans fin de ligne arrive sans '\n'.
         */
        if(rxBuffer.length()>512){
            append("[BLE] Trame incomplète abandonnée\n");
            rxBuffer.setLength(0);
        }
    }

    private void processProtocolLine(String s_line){
        String[] as_fields=s_line.split(",",-1);
        String s_type=as_fields[0];

        try{
            switch(s_type){
                case "D1":
                    if(as_fields.length!=4) throw new IllegalArgumentException();
                    append(String.format(Locale.FRANCE,"[D1] Etat Master : %d | Warning : %d | SOC : %d %%\n",
                        Integer.parseInt(as_fields[1]),Integer.parseInt(as_fields[2]),Integer.parseInt(as_fields[3])));
                    return;

                case "D2":
                    if(as_fields.length!=8) throw new IllegalArgumentException();
                    int i_bal=Integer.parseInt(as_fields[4]);
                    append(String.format(Locale.FRANCE,"Cellule %02d | Cell : %d | Temp : %d | Bal : %s | Target : %d | Delay : %d ms | Error : %d %%\n",
                        Integer.parseInt(as_fields[1]),Integer.parseInt(as_fields[2]),Integer.parseInt(as_fields[3]),
                        i_bal==0?"OFF":Integer.toString(i_bal),Integer.parseInt(as_fields[5]),Long.parseLong(as_fields[6]),Integer.parseInt(as_fields[7])));
                    return;

                case "D3A":
                    if(as_fields.length!=5) throw new IllegalArgumentException();
                    append(String.format(Locale.FRANCE,"[D3] Target balance : %d | Cells in balance : %d | Cells need balance : %d | Courant charge : %.1f %%\n",
                        Integer.parseInt(as_fields[1]),Integer.parseInt(as_fields[2]),Integer.parseInt(as_fields[3]),Integer.parseInt(as_fields[4])/10.0));
                    return;

                case "D3B":
                    if(as_fields.length!=5) throw new IllegalArgumentException();
                    append(String.format(Locale.FRANCE,"[D3] Cell min : ID %d / %d | Cell max : ID %d / %d\n",
                        Integer.parseInt(as_fields[1]),Integer.parseInt(as_fields[2]),Integer.parseInt(as_fields[3]),Integer.parseInt(as_fields[4])));
                    return;

                case "D3C":
                    if(as_fields.length!=6) throw new IllegalArgumentException();
                    append(String.format(Locale.FRANCE,"[D3] Temp min : ID %d / %d | Temp moy : %d | Temp max : ID %d / %d\n",
                        Integer.parseInt(as_fields[1]),Integer.parseInt(as_fields[2]),Integer.parseInt(as_fields[3]),Integer.parseInt(as_fields[4]),Integer.parseInt(as_fields[5])));
                    return;

                case "D4":
                    if(as_fields.length==2 && "0".equals(as_fields[1])){
                        append("[D4] Logbook vide\n");
                        return;
                    }
                    if(as_fields.length!=6) throw new IllegalArgumentException();
                    append(String.format(Locale.FRANCE,"[D4] Log %d | Temps : %d s | Device : %d | Repeat : %d | Error : %d\n",
                        Integer.parseInt(as_fields[1]),Long.parseLong(as_fields[2]),Integer.parseInt(as_fields[3]),Integer.parseInt(as_fields[4]),Integer.parseInt(as_fields[5])));
                    return;

                case "D5A":
                    if(as_fields.length!=5) throw new IllegalArgumentException();
                    append(String.format(Locale.FRANCE,"[D5] WK_BUS : %s | WK_CHRG : %s | DEBUG_BLE : %s | ZIVAN : %s\n",
                        "1".equals(as_fields[1])?"ON":"OFF","1".equals(as_fields[2])?"ON":"OFF","1".equals(as_fields[3])?"ON":"OFF","1".equals(as_fields[4])?"ON":"OFF"));
                    return;

                case "D5B":
                    if(as_fields.length!=7) throw new IllegalArgumentException();
                    append(String.format(Locale.FRANCE,"[D5] Zero A : %d ADC | Zero B : %d ADC | VPACK : %.2f V | VBUS : %.2f V | VISO : %.2f V | Courant : %d A\n",
                        Long.parseLong(as_fields[1]),Long.parseLong(as_fields[2]),Integer.parseInt(as_fields[3])/100.0,Integer.parseInt(as_fields[4])/100.0,
                        Integer.parseInt(as_fields[5])/100.0,Integer.parseInt(as_fields[6])));
                    return;

                case "D6":
                    append("[D6] Non supporté sur Bluetooth\n");
                    return;

                case "D7A":
                    if(as_fields.length!=5) throw new IllegalArgumentException();
                    append(String.format(Locale.FRANCE,"[D7] Charge max : %.1f %% | Traction max : %d A | Regen max : %d A | Balancing : %.2f V\n",
                        Integer.parseInt(as_fields[1])/10.0,Integer.parseInt(as_fields[2]),Integer.parseInt(as_fields[3]),Integer.parseInt(as_fields[4])/100.0));
                    return;

                case "D7B":
                    if(as_fields.length!=4) throw new IllegalArgumentException();
                    append(String.format(Locale.FRANCE,"[D7] SOC OCV : %d %% | SOC Coulomb : %d %% | Energie : %.3f Ah\n",
                        Integer.parseInt(as_fields[1]),Integer.parseInt(as_fields[2]),Long.parseLong(as_fields[3])/1000.0));
                    return;

                default:
                    append(s_line+"\n");
            }
        }catch(Exception e){
            append("[BLE] Paquet invalide : "+s_line+"\n");
        }
    }

    private void startKeepAlive(){
        handler.removeCallbacks(keepAliveTask);
        handler.postDelayed(keepAliveTask,KEEPALIVE_MS);
    }

    private void stopKeepAlive(){handler.removeCallbacks(keepAliveTask);}

    private void enqueueCommand(String c,boolean echo,boolean allowBeforeReady){
        if(Looper.myLooper()==Looper.getMainLooper())enqueueCommandOnMain(c,echo,allowBeforeReady);
        else handler.post(()->enqueueCommandOnMain(c,echo,allowBeforeReady));
    }

    private void enqueueCommandOnMain(String c,boolean echo,boolean allowBeforeReady){
        if(!connected || gatt==null || writeChar==null)return;
        if(!allowBeforeReady && !sessionReady)return;

        String x=c.trim().toUpperCase(Locale.ROOT);
        byte[] p=(x+"\r\n").getBytes(StandardCharsets.US_ASCII);
        if(p.length>20){if(echo)Toast.makeText(this,"Commande trop longue",Toast.LENGTH_SHORT).show();return;}

        writeQueue.addLast(new WriteRequest(x,echo));
        if(echo)append("\n> "+x+"\n");
        pumpWriteQueue();
    }

    private void pumpWriteQueue(){
        if(writeInProgress || writeQueue.isEmpty() || !connected || gatt==null || writeChar==null)return;

        WriteRequest req=writeQueue.pollFirst();
        byte[] payload=(req.command+"\r\n").getBytes(StandardCharsets.US_ASCII);

        try{
            currentWrite=req;
            writeInProgress=true;
            writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            writeChar.setValue(payload);
            if(!gatt.writeCharacteristic(writeChar)){
                writeInProgress=false;
                currentWrite=null;
                if(req.command.equals("BD") && closeAfterBd){closeAfterBd=false;closeGattNow(true);return;}
                pumpWriteQueue();
            }
        }catch(Exception e){
            writeInProgress=false;
            currentWrite=null;
            if(req.command.equals("BD") && closeAfterBd){closeAfterBd=false;closeGattNow(true);return;}
            pumpWriteQueue();
        }
    }

    private void finishCurrentWrite(int st){
        WriteRequest done=currentWrite;
        currentWrite=null;
        writeInProgress=false;

        if(done!=null && st!=BluetoothGatt.GATT_SUCCESS && done.echo)Toast.makeText(this,"Erreur envoi BLE : "+st,Toast.LENGTH_SHORT).show();

        if(done!=null && done.command.equals("BD") && closeAfterBd){
            closeAfterBd=false;
            handler.removeCallbacks(forceDisconnectTask);
            closeGattNow(true);
            return;
        }

        pumpWriteQueue();
    }

    private void send(String c){
        if(!sessionReady || !connected || gatt==null || writeChar==null){Toast.makeText(this,"BMS non connecté",Toast.LENGTH_SHORT).show();return;}
        enqueueCommand(c,true,false);
    }

    private void append(String s){
        terminal.append(s);
        if(terminal.length()>120000){CharSequence t=terminal.getText();terminal.setText(t.subSequence(t.length()-80000,t.length()));}
        terminalScroll.post(()->terminalScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void setStatus(String s,boolean ok){status.setText(s);status.setTextColor(ok?Color.rgb(0,110,55):Color.rgb(170,30,30));}

    private void disconnectGracefully(){
        manualDisconnect=true;
        stopKeepAlive();
        setStatus("Déconnexion…",true);

        if(connected && gatt!=null && writeChar!=null){
            sessionReady=false;
            writeQueue.clear();
            closeAfterBd=true;
            enqueueCommandOnMain("BD",false,true);
            handler.removeCallbacks(forceDisconnectTask);
            handler.postDelayed(forceDisconnectTask,800);
        }else{
            closeGattNow(true);
        }
    }

    private void closeGattNow(boolean showDisconnected){
        stopKeepAlive();
        handler.removeCallbacks(forceDisconnectTask);
        closeAfterBd=false;
        sessionReady=false;
        connected=false;
        writeChar=null;
        writeQueue.clear();
        writeInProgress=false;
        currentWrite=null;

        BluetoothGatt old=gatt;
        gatt=null;
        if(old!=null){
            try{if(perms())old.disconnect();}catch(Exception ignored){}
            try{old.close();}catch(Exception ignored){}
        }

        if(disconnectBtn!=null)disconnectBtn.setEnabled(false);
        if(showDisconnected){
            setStatus("Déconnecté",false);
            append("[APP] Déconnecté\n");
        }
        manualDisconnect=false;
    }

    @Override protected void onDestroy(){
        handler.removeCallbacksAndMessages(null);
        stopScan();
        closeGattNow(false);
        super.onDestroy();
    }
}
