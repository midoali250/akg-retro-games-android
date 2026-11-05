package com.bitvaultengine.retrocollection;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/** An honest menu preview: no ROM loading or emulation is implemented. */
public final class MainActivity extends Activity {
    private static final int BG=Color.rgb(9,16,41), FG=Color.rgb(224,235,250), MUTED=Color.rgb(158,183,216), ACCENT=Color.rgb(96,225,235);
    private final ArrayList<Game> games=new ArrayList<>();
    private GridLayout grid; private TextView count; private String category="ALL",query="";
    static final class Game { final String title,system; Game(String t,String s){title=t;system=s;} }
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private GradientDrawable box(int color,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(10));d.setStroke(dp(1),stroke);return d;}
    private TextView label(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}
    private String asset(String name) throws Exception {try(InputStream in=getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString(StandardCharsets.UTF_8.name());}}
    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        try {JSONObject root=new JSONObject(asset("drive-index.json"));JSONArray a=root.getJSONArray("files"),excluded=root.getJSONArray("excluded_ids");
            for(int i=0;i<a.length();i++){JSONObject item=a.getJSONObject(i);String id=item.getString("id"),name=item.getString("name"),lower=name.toLowerCase(Locale.ROOT);boolean skip=false;for(int j=0;j<excluded.length();j++)if(id.equals(excluded.getString(j)))skip=true;
                if(skip)continue;String system=lower.endsWith(".nes")?"NES":(lower.endsWith(".smc")||lower.endsWith(".sfc")?"SNES":null);if(system!=null)games.add(new Game(name.substring(0,name.lastIndexOf('.')),system));}
        }catch(Exception e){new AlertDialog.Builder(this).setTitle("Catalog error").setMessage(e.getMessage()).setPositiveButton("OK",null).show();}
        build();
    }
    private Button button(String text){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextColor(FG);b.setTextSize(12);b.setBackground(box(Color.rgb(28,43,76),Color.rgb(56,79,113)));return b;}
    private void build(){LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(10),dp(20),dp(6));root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{BG,Color.rgb(26,19,63)}));
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);TextView title=label("AKG RETRO GAMES",22,FG);title.setTypeface(null,Typeface.BOLD);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));Button about=button("About");about.setOnClickListener(v->showAbout());header.addView(about,new LinearLayout.LayoutParams(dp(96),dp(42)));root.addView(header);
        TextView note=label("MENU PREVIEW — GAMES CANNOT YET BE PLAYED",11,Color.rgb(246,183,104));root.addView(note);
        LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER_VERTICAL);
        for(String c:new String[]{"ALL","NES","SNES"}){Button b=button(c);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(dp(76),dp(40));bp.setMargins(0,dp(6),dp(5),dp(6));controls.addView(b,bp);b.setOnClickListener(v->{category=c;render();});}
        EditText search=new EditText(this);search.setSingleLine(true);search.setTextColor(FG);search.setHintTextColor(MUTED);search.setHint("Search games");search.setTextSize(13);search.setPadding(dp(8),0,dp(8),0);search.setBackground(box(Color.rgb(20,32,60),Color.rgb(50,72,104)));controls.addView(search,new LinearLayout.LayoutParams(0,dp(40),1));search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){} public void onTextChanged(CharSequence s,int st,int before,int n){query=s.toString().toLowerCase(Locale.ROOT);render();} public void afterTextChanged(Editable e){}});
        root.addView(controls);count=label("",11,MUTED);root.addView(count);ScrollView scroll=new ScrollView(this);grid=new GridLayout(this);grid.setColumnCount(3);scroll.addView(grid);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);render();}
    private void render(){if(grid==null)return;grid.removeAllViews();int shown=0;int width=Math.max(dp(110),(getResources().getDisplayMetrics().widthPixels-dp(70))/3);
        for(Game g:games){if(!category.equals("ALL")&&!category.equals(g.system))continue;if(!g.title.toLowerCase(Locale.ROOT).contains(query))continue;shown++;
            LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(10),dp(10),dp(10),dp(10));card.setBackground(box(Color.rgb(22,37,66),Color.rgb(62,96,132)));TextView sys=label(g.system,11,ACCENT);card.addView(sys);TextView text=label(g.title,14,FG);text.setMaxLines(2);card.addView(text);card.setOnClickListener(v->Toast.makeText(this,"Preview only: game execution is not implemented",Toast.LENGTH_LONG).show());GridLayout.LayoutParams p=new GridLayout.LayoutParams();p.width=width;p.height=dp(76);p.setMargins(dp(3),dp(3),dp(3),dp(3));grid.addView(card,p);}
        count.setText(shown+" games  •  developer and disclaimer: About");}
    private void showAbout(){try{new AlertDialog.Builder(this).setTitle("Developer and disclaimer").setMessage(asset("NOTICE.md")).setPositiveButton("Close",null).show();}catch(Exception e){Toast.makeText(this,"Notice unavailable",Toast.LENGTH_SHORT).show();}}
}
