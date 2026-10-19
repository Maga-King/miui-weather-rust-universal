package dev.weather.oneplus;

import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontStyle;
import android.graphics.fonts.FontVariationAxis;
import android.graphics.text.PositionedGlyphs;
import android.graphics.text.TextRunShaper;
import android.os.Build;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** In-app text faces. Widget weights and their rendering path stay independent. */
final class WeatherTextFonts {
    private static final Map<String,Typeface> cache=new LinkedHashMap<String,Typeface>(32,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Typeface> entry){return size()>64;}
    };
    private WeatherTextFonts(){}
    static int miSansAxis(int weight) {
        int[] weights={100,200,300,350,400,500,600,700,800,900};
        int[] axes={150,200,250,305,330,380,450,450,520,600};
        for(int i=1;i<weights.length;i++)if(weight<=weights[i])
            return Math.round(axes[i-1]+(axes[i]-axes[i-1])*(weight-weights[i-1])/(float)(weights[i]-weights[i-1]));
        return 600;
    }
    static synchronized Typeface systemFace(Typeface original,int base,int weight,boolean italic) {
        if(weight==base)return original;
        String key=System.identityHashCode(original)+":"+base+":"+weight+":"+italic;
        Typeface result=cache.get(key);if(result!=null)return result;
        if(Build.VERSION.SDK_INT>=31)try {
            Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setTypeface(original);paint.setTextSize(24);
            // Resolve the fonts actually used by Android, including the selected
            // OEM font. Rebuild a custom face so ColorOS cannot apply its second
            // "bold -> medium" correction at the 600-weight threshold.
            String sample="Weather 123\u5929\u6c14\u9884\u62a5";
            PositionedGlyphs glyphs=TextRunShaper.shapeTextRun(sample,0,sample.length(),0,sample.length(),0,0,false,paint);
            ArrayList<Font> fonts=new ArrayList<>();
            for(int i=0;i<glyphs.glyphCount();i++) {
                Font font=glyphs.getFont(i);boolean found=false;
                for(Font prior:fonts)if(prior.equals(font)){found=true;break;}
                if(!found)fonts.add(font);
            }
            Typeface.CustomFallbackBuilder builder=null;
            for(Font font:fonts) {
                float[] range=weightRange(font);
                if(range==null)continue;
                float axis=range[1];
                FontVariationAxis[] old=font.getAxes();
                if(old!=null)for(FontVariationAxis a:old)if("wght".equals(a.getTag()))axis=a.getStyleValue();
                axis=Math.max(range[0],Math.min(range[2],axis*weight/(float)base));
                StringBuilder settings=new StringBuilder("'wght' ").append(axis);
                if(old!=null)for(FontVariationAxis a:old)if(!"wght".equals(a.getTag()))
                    settings.append(",'").append(a.getTag()).append("' ").append(a.getStyleValue());
                Font variant=new Font.Builder(font).setFontVariationSettings(settings.toString())
                        .setWeight(weight).setSlant(italic?1:0).build();
                FontFamily family=new FontFamily.Builder(variant).build();
                if(builder==null)builder=new Typeface.CustomFallbackBuilder(family);
                else builder.addCustomFallback(family);
            }
            if(builder!=null)result=builder.setStyle(new FontStyle(weight,italic?1:0)).setSystemFallback("sans-serif").build();
        } catch(Exception error){android.util.Log.w("WeatherFonts","System VF unavailable",error);}
        if(result==null)result=Typeface.create(original,weight,italic);
        cache.put(key,result);return result;
    }
    /** Read fvar bounds without hidden OEM APIs; also handles a TTC font index. */
    private static float[] weightRange(Font font) {
        ByteBuffer b=font.getBuffer().duplicate().order(ByteOrder.BIG_ENDIAN);int sfnt=0;
        if(b.getInt(0)==0x74746366)sfnt=b.getInt(12+4*font.getTtcIndex());
        int tables=b.getShort(sfnt+4)&65535;
        for(int i=0;i<tables;i++) {
            int table=sfnt+12+16*i;if(b.getInt(table)!=0x66766172)continue;
            int fvar=b.getInt(table+8),start=fvar+(b.getShort(fvar+4)&65535);
            int count=b.getShort(fvar+8)&65535,stride=b.getShort(fvar+10)&65535;
            for(int j=0;j<count;j++) {
                int axis=start+j*stride;
                if(b.getInt(axis)==0x77676874)return new float[]{b.getInt(axis+4)/65536f,b.getInt(axis+8)/65536f,b.getInt(axis+12)/65536f};
            }
        }
        return null;
    }
}
