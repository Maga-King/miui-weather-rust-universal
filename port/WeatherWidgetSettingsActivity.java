package dev.weather.oneplus;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MenuItem;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import miuix.appcompat.app.ActionBar;
import miuix.appcompat.app.AppCompatActivity;

/** Uses the same MIUIX runtime and resources as the original weather detail pages. */
public abstract class WeatherWidgetSettingsActivity extends AppCompatActivity {
    protected boolean chinese, dark;
    protected int foreground, secondary;

    @Override protected void attachBaseContext(Context context) {
        Context wrapped=WeatherDisplayDensity.wrap(context);
        WeatherPagesRuntime.install(wrapped);
        super.attachBaseContext(wrapped);
        WeatherPagesRuntime.install(this);
    }

    @Override public void onCreate(Bundle state) {
        dark=(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        chinese="zh".equals(getResources().getConfiguration().getLocales().get(0).getLanguage());
        foreground=dark?0xffeeeeee:0xff181818;
        secondary=dark?0xffa5a5a5:0xff777777;
        setTheme(dark?0x7f1301d0:0x7f1301f6);
        super.onCreate(state);
        ActionBar bar=getAppCompatActionBar();
        if(bar!=null){bar.v(true);bar.N(false);}
        if(android.os.Build.VERSION.SDK_INT>=33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::finish);
        }
    }

    @Override public boolean onMenuItemSelected(int feature,MenuItem item) {
        if(item.getItemId()==android.R.id.home){finish();return true;}
        return super.onMenuItemSelected(feature,item);
    }

    protected int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}

    protected LinearLayout page() {
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);scroll.setClipToPadding(false);
        scroll.setBackgroundColor(dark?0xff101010:0xfff5f5f5);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20),dp(12),dp(20),dp(24));
        scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        scroll.setOnApplyWindowInsetsListener((view,insets)->{
            view.setPadding(0,0,0,insets.getSystemWindowInsetBottom());return insets;
        });
        setContentView(scroll);scroll.requestApplyInsets();
        scroll.post(()->WeatherFonts.apply(this));return content;
    }

    protected TextView text(String value,float size,boolean muted) {
        TextView text=new TextView(this);text.setText(value);text.setTextSize(size);
        text.setTextColor(muted?secondary:foreground);text.setGravity(Gravity.CENTER_VERTICAL);
        WeatherFonts.apply(this,text);return text;
    }

    protected void note(LinearLayout parent,String value) {
        TextView text=text(value,13,true);text.setLineSpacing(dp(3),1);
        text.setPadding(dp(12),dp(14),dp(12),dp(14));
        parent.addView(text,new LinearLayout.LayoutParams(-1,-2));
    }

    protected LinearLayout card(LinearLayout parent) {
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg=new GradientDrawable();bg.setColor(dark?0xff202020:Color.WHITE);bg.setCornerRadius(dp(18));
        card.setBackground(bg);card.setPadding(dp(18),dp(10),dp(18),dp(10));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(12);
        parent.addView(card,params);return card;
    }

    protected void link(LinearLayout parent,String title,String detail,Runnable click) {
        LinearLayout row=card(parent);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(82));
        LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title,17,false),new LinearLayout.LayoutParams(-1,-2));
        TextView summary=text(detail,13,true);summary.setPadding(0,dp(5),0,0);
        labels.addView(summary,new LinearLayout.LayoutParams(-1,-2));row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        TextView arrow=text("\u203a",28,true);arrow.setPadding(dp(12),0,0,0);row.addView(arrow,new LinearLayout.LayoutParams(-2,-1));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(dark?0x22ffffff:0x15000000),row.getBackground(),null));
        row.setFocusable(true);row.setOnClickListener(v->click.run());row.setContentDescription(title+", "+detail);
    }
}
