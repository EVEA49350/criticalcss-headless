package com.evea.bmsterminal;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.pm.PackageManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
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
    private boolean authFailure=false;
    private boolean bondInProgress=false;
    private boolean bondFailed=false;
    private boolean servicesRequested=false;
    private boolean bondReceiverRegistered=false;
    private Runnable discoveryTask=null;
    private Runnable bondingTimeoutTask=null;

    private final BroadcastReceiver bondReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            if(!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction()))return;
            BluetoothDevice d=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if(d==null || currentDevice==null || !d.getAddress().equals(currentDevice.getAddress()))return;
            int state=intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,BluetoothDevice.ERROR);
            int previous=intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,BluetoothDevice.ERROR);
            trace("Association : "+previous+" → "+state);
            if(gatt==null)return;
            if(state==BluetoothDevice.BOND_BONDING){
                bondInProgress=true;
                cancelDiscovery();
                setStatus("Association Bluetooth en cours…",true);
                scheduleBondingTimeout(gatt);
            }else if(state==BluetoothDevice.BOND_BONDED){
                bondInProgress=false;
                cancelBondingTimeout();
                setStatus("Associé — stabilisation BLE…",true);
                scheduleDiscovery(gatt,1600);
            }else if(state==BluetoothDevice.BOND_NONE && (previous==BluetoothDevice.BOND_BONDING || bondInProgress)){
                bondInProgress=false;
                bondFailed=true;
                authFailure=true;
                cancelDiscovery();
                cancelBondingTimeout();
                setStatus("Association refusée ou annulée",false);
                trace("ÉCHEC association : abandon de cette tentative, sans reconnexion automatique");
                if(gatt!=null){try{gatt.disconnect();}catch(Exception ignored){}}
            }
        }
    };

    private void cancelDiscovery(){
        if(discoveryTask!=null){handler.removeCallbacks(discoveryTask);discoveryTask=null;}
    }
    private void cancelBondingTimeout(){
        if(bondingTimeoutTask!=null){handler.removeCallbacks(bondingTimeoutTask);bondingTimeoutTask=null;}
    }
    private void scheduleBondingTimeout(BluetoothGatt active){
        cancelBondingTimeout();
        bondingTimeoutTask=()->{
            if(active==gatt && bondInProgress && !sessionReady){
                bondFailed=true;authFailure=true;
                trace("Association toujours en cours après 30 s : abandon sans nouvelle tentative");
                setStatus("Délai d'association dépassé",false);
                try{active.disconnect();}catch(Exception ignored){}
            }
        };
        handler.postDelayed(bondingTimeoutTask,30000);
    }
    private void scheduleDiscovery(BluetoothGatt active,long delay){
        cancelDiscovery();
        if(active==null || servicesRequested || bondFailed)return;
        discoveryTask=()->{
            discoveryTask=null;
            if(active!=gatt || !connected || servicesRequested || bondFailed)return;
            if(bondInProgress || bondState(currentDevice).equals("BONDING")){
                trace("Découverte différée : association encore en cours");
                return;
            }
            servicesRequested=true;
            trace("Début découverte services : bond="+bondState(currentDevice));
            setStatus("Découverte des services…",true);
            try{
                if(!active.discoverServices()){
                    servicesRequested=false;
                    setStatus("Échec découverte des services",false);
                }
            }catch(Exception ex){servicesRequested=false;setStatus("Erreur découverte des services",false);}
        };
        handler.postDelayed(discoveryTask,delay);
    }

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
        IntentFilter bondFilter=new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(bondReceiver,bondFilter,Context.RECEIVER_EXPORTED);
        else registerReceiver(bondReceiver,bondFilter);
        bondReceiverRegistered=true;

        BluetoothManager m=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        adapter=m==null?null:m.getAdapter();
        if(adapter==null){setStatus("Bluetooth non disponible",false);scanBtn.setEnabled(false);return;}
        requestOrScan();
    }


    private static final int BACKGROUND=Color.rgb(13,16,19), PANEL=Color.rgb(24,29,34), BORDER=Color.rgb(61,70,77);
    private static final int FOREGROUND=Color.WHITE, MUTED=Color.rgb(170,184,194), GREEN=Color.rgb(74,196,120);
    private LinearLayout connectionPanel,debugPanel;
    private TextView screenTitle,connectionLog;
    private LinearLayout systemPanel,systemErrorCard;
    private TextView systemState,systemDevice,systemRepeat,systemErrorName;
    private SocGauge socGauge;
    private int socValue=-1;
    private int masterStateValue=-1;
    private final StringBuilder diagnostics=new StringBuilder();
    private int activeScreen=0;
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private android.graphics.drawable.GradientDrawable background(int fill,int stroke,int radius){
        android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();
        d.setColor(fill);d.setCornerRadius(dp(radius));if(stroke!=0)d.setStroke(dp(1),stroke);return d;
    }
    private Button button(String label){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextColor(FOREGROUND);b.setTextSize(13);
        b.setBackground(background(PANEL,BORDER,12));b.setPadding(dp(5),0,dp(5),0);return b;
    }
    private TextView text(String label,int size,int color){
        TextView t=new TextView(this);t.setText(label);t.setTextSize(size);t.setTextColor(color);return t;
    }
    private final class SocGauge extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arc=new RectF();
        SocGauge(){super(MainActivity.this);setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            int width=MeasureSpec.getSize(widthSpec);
            int height=Math.min(dp(270),Math.max(dp(190),width*3/4));
            setMeasuredDimension(width,height);
        }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            float w=getWidth(),h=getHeight(),cx=w/2f,cy=h*0.68f;
            float r=Math.min(w*0.38f,h*0.53f);
            float sw=Math.max(dp(13),r*0.125f);
            arc.set(cx-r,cy-r,cx+r,cy+r);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(sw);p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(Color.rgb(57,65,73));c.drawArc(arc,150,240,false,p);
            int level=Math.max(0,Math.min(100,socValue));
            int accent=level<=15?Color.rgb(237,111,92):level<=30?Color.rgb(231,171,78):GREEN;
            if(socValue>=0){p.setColor(accent);c.drawArc(arc,150,240f*level/100f,false,p);}
            // Aiguille : 150° à gauche (0 %) vers 390° à droite (100 %).
            if(socValue>=0){
                double angle=Math.toRadians(150+240.0*level/100.0);
                float nx=cx+(float)Math.cos(angle)*r*0.80f;
                float ny=cy+(float)Math.sin(angle)*r*0.80f;
                p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(3));p.setColor(FOREGROUND);
                c.drawLine(cx,cy,nx,ny,p);
                p.setStyle(Paint.Style.FILL);c.drawCircle(cx,cy,dp(6),p);
            }
            p.setStyle(Paint.Style.FILL);p.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));
            p.setTextAlign(Paint.Align.CENTER);p.setColor(FOREGROUND);p.setTextSize(dp(43));
            c.drawText(socValue>=0?socValue+" %":"—",cx,cy-r*0.28f,p);
            p.setTypeface(Typeface.DEFAULT);p.setTextSize(dp(13));p.setColor(MUTED);
            c.drawText("ÉTAT DE CHARGE",cx,cy-r*0.06f,p);
            p.setTextSize(dp(12));c.drawText("0",cx-r*1.03f,cy+r*0.65f,p);
            c.drawText("100",cx+r*1.03f,cy+r*0.65f,p);
        }
    }
    private TextView systemLabel(String label){
        TextView t=text(label,12,MUTED);t.setTypeface(null,Typeface.BOLD);
        t.setPadding(0,0,0,dp(9));return t;
    }
    private void buildSystemUi(){
        systemPanel=new LinearLayout(this);systemPanel.setOrientation(1);
        systemPanel.setVisibility(View.GONE);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);
        LinearLayout body=new LinearLayout(this);body.setOrientation(1);
        socGauge=new SocGauge();
        LinearLayout gaugeCard=new LinearLayout(this);gaugeCard.setOrientation(1);
        gaugeCard.setPadding(dp(10),dp(8),dp(10),dp(8));gaugeCard.setBackground(background(PANEL,BORDER,14));
        gaugeCard.addView(socGauge,new LinearLayout.LayoutParams(-1,dp(235)));
        LinearLayout.LayoutParams cardLp=new LinearLayout.LayoutParams(-1,-2);cardLp.bottomMargin=dp(12);
        body.addView(gaugeCard,cardLp);
        LinearLayout stateCard=new LinearLayout(this);stateCard.setOrientation(1);
        stateCard.setPadding(dp(18),dp(16),dp(18),dp(18));stateCard.setBackground(background(PANEL,BORDER,14));
        stateCard.addView(systemLabel("ÉTAT DU MASTER"));
        systemState=text("En attente des données",22,FOREGROUND);
        systemState.setTypeface(null,Typeface.BOLD);stateCard.addView(systemState);
        LinearLayout.LayoutParams stateLp=new LinearLayout.LayoutParams(-1,-2);stateLp.bottomMargin=dp(12);
        body.addView(stateCard,stateLp);
        systemErrorCard=new LinearLayout(this);systemErrorCard.setOrientation(1);
        systemErrorCard.setPadding(dp(18),dp(16),dp(18),dp(18));
        systemErrorCard.setBackground(background(Color.rgb(53,29,31),Color.rgb(182,77,72),14));
        systemErrorCard.addView(systemLabel("DÉFAUT ACTIF"));
        systemErrorName=text("Détails indisponibles",17,Color.rgb(255,210,207));
        systemErrorName.setTypeface(null,Typeface.BOLD);
        systemErrorCard.addView(systemErrorName);
        systemDevice=text("",14,FOREGROUND);systemDevice.setPadding(0,dp(12),0,0);
        systemErrorCard.addView(systemDevice);
        systemRepeat=text("",14,FOREGROUND);systemRepeat.setPadding(0,dp(5),0,0);
        systemErrorCard.addView(systemRepeat);
        systemErrorCard.setVisibility(View.GONE);
        body.addView(systemErrorCard,new LinearLayout.LayoutParams(-1,-2));
        scroll.addView(body);systemPanel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        updateSystemUi();
    }
    // Correspondance des états numériques du Master dans le protocole D1.
    private String masterStateName(int state){
        String[] names={"Initialisation","Réveil des slaves","Attente démarrage","Scan du bus",
            "Interrogation du bus","Attente ID","Scan ID par défaut","Interrogation ID par défaut",
            "Attribution ID","Scan nouvel ID","Interrogation nouvel ID","Enregistrement ID",
            "Configuration terminée","Erreur","Veille","Réveil","Contrôle d'isolement",
            "Capteurs de courant","Précharge","Attente","Charge","Bus actif","Réinitialisation"};
        return state>=0&&state<names.length?names[state]:"État "+state;
    }
    private void updateSystemUi(){
        if(socGauge!=null)socGauge.invalidate();
        if(systemState==null)return;
        boolean error=masterStateValue==13;
        systemState.setText(masterStateValue<0?"En attente des données":masterStateName(masterStateValue));
        systemState.setTextColor(error?Color.rgb(248,121,112):
            masterStateValue==14?MUTED:GREEN);
        if(systemErrorCard!=null)systemErrorCard.setVisibility(error?View.VISIBLE:View.GONE);
    }
    private void applyD1(int state,int warning,int soc){
        masterStateValue=state;socValue=Math.max(0,Math.min(100,soc));
        updateSystemUi();
    }
    private void updateError(String device,String count,String detail){
        if(systemErrorName!=null)systemErrorName.setText(detail.isEmpty()?"Détails non transmis":detail);
        if(systemDevice!=null)systemDevice.setText(device.isEmpty()?"":"Origine : "+device);
        if(systemRepeat!=null)systemRepeat.setText(count.isEmpty()?"":"Répétitions : "+count);
    }
    private void parseDiagnosticText(String line){
        // Compatibilité avec d'éventuelles lignes texte émises par le Master.
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("DEVICE STATE\\s*:\\s*MASTER_([A-Z_]+)",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(line);
        if(m.find()){
            String state=m.group(1).toUpperCase(Locale.ROOT);
            if(state.equals("SLEEP"))masterStateValue=14;
            else if(state.equals("ERROR"))masterStateValue=13;
            else if(state.equals("WAKE_UP"))masterStateValue=15;
            updateSystemUi();
        }
        m=java.util.regex.Pattern.compile("BATTERY SOC VALUE\\s*:\\s*(\\d+)",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(line);
        if(m.find()){socValue=Math.max(0,Math.min(100,Integer.parseInt(m.group(1))));updateSystemUi();}
        m=java.util.regex.Pattern.compile("Active Error\\s*[-:]\\s*Device\\s*:\\s*(.*?)\\s*\\|\\s*Repeat\\s*:\\s*(\\d+)\\s*\\|\\s*(.+)",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(line);
        if(m.find())updateError(m.group(1).trim(),m.group(2).trim(),m.group(3).trim());
    }
    private void buildUi(){
        getWindow().setStatusBarColor(BACKGROUND);getWindow().setNavigationBarColor(BACKGROUND);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(BACKGROUND);
        root.setPadding(dp(14),dp(10),dp(14),dp(10));
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout headerText=new LinearLayout(this);headerText.setOrientation(1);
        TextView title=text("EVEA BMS Terminal",21,FOREGROUND);title.setTypeface(null,1);headerText.addView(title);
        status=text("Déconnecté",12,Color.rgb(240,130,130));status.setPadding(0,dp(5),0,0);
        headerText.addView(status);header.addView(headerText,new LinearLayout.LayoutParams(0,dp(65),1));
        Button menuBtn=button("☰");menuBtn.setTextSize(24);menuBtn.setContentDescription("Navigation");
        menuBtn.setOnClickListener(v->showNavigationMenu());
        header.addView(menuBtn,new LinearLayout.LayoutParams(dp(55),dp(50)));root.addView(header);
        View line=new View(this);line.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams lineP=new LinearLayout.LayoutParams(-1,dp(1));lineP.setMargins(0,dp(3),0,dp(12));
        root.addView(line,lineP);
        screenTitle=text("Connexion",17,FOREGROUND);screenTitle.setTypeface(null,1);
        screenTitle.setPadding(0,0,0,dp(12));root.addView(screenTitle);

        connectionPanel=new LinearLayout(this);connectionPanel.setOrientation(1);
        LinearLayout bar=new LinearLayout(this);
        scanBtn=button("SCAN");scanBtn.setOnClickListener(v->requestOrScan());
        disconnectBtn=button("DÉCONNECTER");disconnectBtn.setEnabled(false);
        disconnectBtn.setOnClickListener(v->disconnectGracefully());
        Button clear=button("EFFACER");clear.setOnClickListener(v->{
            terminal.setText("");rxBuffer.setLength(0);diagnostics.setLength(0);connectionLog.setText("");
        });
        for(Button b:new Button[]{scanBtn,disconnectBtn,clear}){
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(47),b==disconnectBtn?1.5f:1);
            p.rightMargin=dp(5);bar.addView(b,p);
        }
        connectionPanel.addView(bar);
        listAdapter=new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,rows){
            @Override public View getView(int position,View reuse,android.view.ViewGroup parent){
                TextView v=(TextView)super.getView(position,reuse,parent);
                v.setTextColor(FOREGROUND);v.setTextSize(14);v.setPadding(dp(12),dp(10),dp(12),dp(10));
                v.setBackground(background(PANEL,BORDER,9));return v;
            }
        };
        ListView list=new ListView(this);list.setAdapter(listAdapter);list.setDividerHeight(dp(6));
        list.setBackgroundColor(BACKGROUND);
        list.setOnItemClickListener((p,v,pos,id)->{stopScan();beginConnection(found.get(pos));});
        connectionPanel.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout diagHead=new LinearLayout(this);diagHead.setGravity(Gravity.CENTER_VERTICAL);
        diagHead.addView(text("Journal de connexion",14,MUTED),new LinearLayout.LayoutParams(0,dp(44),1));
        Button copy=button("COPIER");copy.setOnClickListener(v->{
            android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            if(clipboard!=null)clipboard.setPrimaryClip(android.content.ClipData.newPlainText("EVEA BLE",diagnostics.toString()));
            Toast.makeText(this,"Journal copié",Toast.LENGTH_SHORT).show();
        });
        diagHead.addView(copy,new LinearLayout.LayoutParams(dp(90),dp(39)));connectionPanel.addView(diagHead);
        connectionLog=text("",12,MUTED);connectionLog.setTypeface(android.graphics.Typeface.MONOSPACE);
        connectionLog.setPadding(dp(8),dp(8),dp(8),dp(8));connectionLog.setBackground(background(PANEL,BORDER,9));
        ScrollView diagScroll=new ScrollView(this);diagScroll.addView(connectionLog);
        connectionPanel.addView(diagScroll,new LinearLayout.LayoutParams(-1,dp(165)));
        root.addView(connectionPanel,new LinearLayout.LayoutParams(-1,0,1));

        debugPanel=new LinearLayout(this);debugPanel.setOrientation(1);debugPanel.setVisibility(View.GONE);
        terminal=text("",13,Color.rgb(226,240,226));terminal.setTypeface(android.graphics.Typeface.MONOSPACE);
        terminal.setTextIsSelectable(true);terminal.setPadding(dp(9),dp(9),dp(9),dp(9));
        terminal.setBackground(background(Color.rgb(19,26,22),BORDER,10));
        terminalScroll=new ScrollView(this);terminalScroll.addView(terminal);
        debugPanel.addView(terminalScroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions=new LinearLayout(this);
        for(String c:new String[]{"LB","++","H?","RT"}){
            Button b=button(c);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(46),1);
            p.setMargins(0,dp(8),dp(5),0);actions.addView(b,p);
            if(c.equals("RT"))b.setOnClickListener(v->new AlertDialog.Builder(this)
                .setTitle("Reset du BMS").setMessage("Envoyer RT au Master ?")
                .setNegativeButton("Annuler",null).setPositiveButton("RESET",(d,w)->send("RT")).show());
            else b.setOnClickListener(v->send(c));
        }
        debugPanel.addView(actions);
        LinearLayout cmdBar=new LinearLayout(this);cmdBar.setGravity(Gravity.CENTER_VERTICAL);
        command=new EditText(this);command.setHint("Commande");command.setSingleLine(true);
        command.setTextColor(FOREGROUND);command.setHintTextColor(MUTED);
        cmdBar.addView(command,new LinearLayout.LayoutParams(0,dp(50),1));
        Button sendBtn=button("ENVOYER");sendBtn.setOnClickListener(v->{
            String c=command.getText().toString().trim();if(!c.isEmpty()){send(c);command.setText("");}
        });
        cmdBar.addView(sendBtn,new LinearLayout.LayoutParams(dp(100),dp(45)));debugPanel.addView(cmdBar);
        root.addView(debugPanel,new LinearLayout.LayoutParams(-1,0,1));
        buildSystemUi();
        root.addView(systemPanel,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
    }
    private void showNavigationMenu(){
        android.app.Dialog dialog=new android.app.Dialog(this);
        LinearLayout outer=new LinearLayout(this);outer.setOrientation(1);
        outer.setPadding(dp(10),dp(10),dp(10),dp(10));outer.setBackground(background(PANEL,BORDER,16));
        TextView caption=text("NAVIGATION",12,MUTED);caption.setPadding(dp(12),dp(7),0,dp(12));outer.addView(caption);
        ScrollView scroll=new ScrollView(this);LinearLayout entries=new LinearLayout(this);entries.setOrientation(1);
        for(int i=0;i<=7;i++){
            int destination=i;boolean selected=(i==activeScreen);
            LinearLayout line=new LinearLayout(this);line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(dp(12),0,dp(12),0);
            line.setBackground(background(selected?Color.rgb(40,57,49):PANEL,selected?GREEN:BORDER,8));
            TextView name=text(i==0?"⌁  Connexion":i==1?"▣  État système":"▣  Debug "+i,16,FOREGROUND);
            line.addView(name,new LinearLayout.LayoutParams(0,dp(54),1));
            if(selected)line.addView(text("✓",21,GREEN));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(54));lp.bottomMargin=dp(5);
            entries.addView(line,lp);
            line.setOnClickListener(v->{dialog.dismiss();selectScreen(destination);});
        }
        scroll.addView(entries);outer.addView(scroll);
        dialog.setContentView(outer);dialog.show();
        Window w=dialog.getWindow();
        if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setLayout(getResources().getDisplayMetrics().widthPixels-dp(32),-2);
            w.setGravity(Gravity.TOP|Gravity.RIGHT);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams a=w.getAttributes();a.dimAmount=0.55f;w.setAttributes(a);
        }
    }
    private void selectScreen(int screen){
        if(screen==activeScreen)return;
        terminal.setText("");rxBuffer.setLength(0);activeScreen=screen;
        boolean connection=screen==0;boolean system=screen==1;
        screenTitle.setText(connection?"Connexion":system?"État système":"Debug "+screen);
        connectionPanel.setVisibility(connection?View.VISIBLE:View.GONE);
        debugPanel.setVisibility(connection||system?View.GONE:View.VISIBLE);
        systemPanel.setVisibility(system?View.VISIBLE:View.GONE);
        if(connection){if(sessionReady)enqueueCommandOnMain("D0",false,false);}
        else if(sessionReady)enqueueCommandOnMain("D"+screen,false,false);
        else Toast.makeText(this,"Connecte d'abord le BMS depuis Connexion",Toast.LENGTH_SHORT).show();
        if(screen==6)append("[D6] Non supporté sur Bluetooth\n");
    }
    private void trace(String event){
        String record=String.format(Locale.ROOT,"%1$tT  %2$s\n",new Date(),event);
        diagnostics.append(record);if(diagnostics.length()>12000)diagnostics.delete(0,diagnostics.length()-9000);
        if(connectionLog!=null)connectionLog.setText(diagnostics.toString());
    }
    private void traceOnMain(String event){runOnUiThread(()->trace(event));}
    private String bondState(BluetoothDevice d){
        if(d==null)return "null";
        try{int state=d.getBondState();
            return state==BluetoothDevice.BOND_BONDED?"BONDED":state==BluetoothDevice.BOND_BONDING?"BONDING":"NONE";
        }catch(Exception ex){return "indisponible";}
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
        authFailure=false;
        bondInProgress=false;
        bondFailed=false;
        servicesRequested=false;
        cancelDiscovery();
        cancelBondingTimeout();
        trace("Connexion demandée : bond = "+bondState(d));
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
        trace("connectGatt, tentative "+(reconnectAttempt+1)+", bond = "+bondState(d));
        servicesRequested=false;
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
            traceOnMain("GATT : état="+ns+", statut="+st+", bond="+bondState(currentDevice));

            if(ns==BluetoothProfile.STATE_CONNECTED && st==BluetoothGatt.GATT_SUCCESS){
                connected=true;
                runOnUiThread(()->{
                    disconnectBtn.setEnabled(true);
                    setStatus("Connecté — découverte des services…",true);
                    append("[APP] Connecté\n");
                });
                handler.post(()->{
                    if(g!=gatt || !connected)return;
                    String state=bondState(currentDevice);
                    trace("Connexion établie : bond="+state+", attente avant découverte");
                    if(state.equals("BONDING")){
                        bondInProgress=true;
                        setStatus("Association Bluetooth en cours…",true);
                        scheduleBondingTimeout(g);
                    }else{
                        // Laisser à Android le temps de démarrer une éventuelle
                        // réassociation avant toute opération GATT supplémentaire.
                        scheduleDiscovery(g,state.equals("BONDED")?1600:1800);
                    }
                });
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

                // Ne jamais relancer automatiquement une association qui a échoué.
                // L’utilisateur peut réessayer depuis l’écran Connexion.
                boolean wasBondFailure=bondFailed || bondInProgress || authFailure;
                handler.post(()->{cancelDiscovery();cancelBondingTimeout();});

                try{g.close();}catch(Exception ignored){}
                if(g==gatt)gatt=null;

                runOnUiThread(()->{
                    disconnectBtn.setEnabled(false);
                    if(wasBondFailure){
                        setStatus("Association Bluetooth échouée",false);
                        trace("GATT interrompu après échec d'association ; statut="+st);
                    }else{
                        setStatus("Déconnecté (GATT "+st+")",false);
                        trace("GATT déconnecté ; statut="+st+" ; aucune reconnexion automatique");
                    }
                    append("[APP] Déconnecté\n");
                });
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g,int st){
            if(g!=gatt)return;
            traceOnMain("Découverte services : statut="+st+", bond="+bondState(currentDevice));
            if(st!=BluetoothGatt.GATT_SUCCESS){runOnUiThread(()->setStatus("Erreur découverte services : "+st,false));return;}

            BluetoothGattService s=g.getService(SVC);
            if(s==null){runOnUiThread(()->setStatus("Service FFE0 introuvable",false));return;}

            BluetoothGattCharacteristic c1=s.getCharacteristic(CH1),c2=s.getCharacteristic(CH2);
            if(c1==null){runOnUiThread(()->setStatus("FFE1 introuvable",false));return;}

            writeChar=((c1.getProperties()&(BluetoothGattCharacteristic.PROPERTY_WRITE|BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE))!=0)?c1:c2;
            if(writeChar==null){runOnUiThread(()->setStatus("Caractéristique d'écriture introuvable",false));return;}

            traceOnMain("FFE1 propriétés="+c1.getProperties()+", FFE2 propriétés="+(c2==null?"absente":c2.getProperties())+", bond="+bondState(currentDevice));
            try{
                if(!g.setCharacteristicNotification(c1,true)){runOnUiThread(()->setStatus("Activation notifications refusée",false));return;}
                BluetoothGattDescriptor d=c1.getDescriptor(CCCD);
                if(d==null){runOnUiThread(()->setStatus("CCCD 0x2902 introuvable",false));return;}
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                traceOnMain("Écriture CCCD FFE1 ; bond="+bondState(currentDevice));
                if(!g.writeDescriptor(d))runOnUiThread(()->{trace("writeDescriptor a retourné false");setStatus("Échec écriture CCCD",false);});
            }catch(Exception e){
                runOnUiThread(()->setStatus("Erreur activation notifications",false));
            }
        }

        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int st){
            if(g!=gatt)return;
            runOnUiThread(()->{
                trace("Réponse CCCD : statut="+st+", bond="+bondState(currentDevice));
                if(st==BluetoothGatt.GATT_SUCCESS){
                    sessionReady=true;
                    reconnectAttempt=0;
                    setStatus("Connecté à EVEA-BMS-MASTER",true);
                    append("[APP] Notifications FFE1 actives\n");
                    enqueueCommandOnMain("BC",false,true);
                    startKeepAlive();
                }else{
                    if(st==5 || st==15){authFailure=true;trace("Erreur de sécurité GATT : association répétée potentielle");}
                    setStatus("Erreur notifications : "+st,false);
                }
            });
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int st){
            if(g!=gatt)return;
            if(st!=BluetoothGatt.GATT_SUCCESS)traceOnMain("Écriture BLE échouée : statut="+st+", bond="+bondState(currentDevice));
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
                    int state=Integer.parseInt(as_fields[1]);
                    int warning=Integer.parseInt(as_fields[2]);
                    int soc=Integer.parseInt(as_fields[3]);
                    applyD1(state,warning,soc);
                    append(String.format(Locale.FRANCE,"[D1] Etat Master : %d | Warning : %d | SOC : %d %%\n",state,warning,soc));
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
                    parseDiagnosticText(s_line);
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
        cancelDiscovery();
        cancelBondingTimeout();
        bondInProgress=false;
        servicesRequested=false;
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
        if(bondReceiverRegistered){unregisterReceiver(bondReceiver);bondReceiverRegistered=false;}
        handler.removeCallbacksAndMessages(null);
        stopScan();
        closeGattNow(false);
        super.onDestroy();
    }
}
