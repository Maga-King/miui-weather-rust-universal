package dev.weather.oneplus;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Flutter StandardMessageCodec wire format. */
final class Codec {
    static Object read(ByteBuffer b) {
        int t=b.get()&255;
        switch(t) {
            case 0:return null; case 1:return true; case 2:return false;
            case 3:return b.getInt(); case 4:return b.getLong();
            case 6:align(b,8);return b.getDouble();
            case 5:case 7:{byte[] a=new byte[size(b)];b.get(a);return new String(a,StandardCharsets.UTF_8);}
            case 8:{byte[] a=new byte[size(b)];b.get(a);return a;}
            case 9:{int n=size(b);align(b,4);int[] a=new int[n];for(int i=0;i<n;i++)a[i]=b.getInt();return a;}
            case 10:{int n=size(b);align(b,8);long[] a=new long[n];for(int i=0;i<n;i++)a[i]=b.getLong();return a;}
            case 11:{int n=size(b);align(b,8);double[] a=new double[n];for(int i=0;i<n;i++)a[i]=b.getDouble();return a;}
            case 12:{int n=size(b);List<Object>a=new ArrayList<>();for(int i=0;i<n;i++)a.add(read(b));return a;}
            case 13:{int n=size(b);Map<Object,Object>a=new LinkedHashMap<>();for(int i=0;i<n;i++)a.put(read(b),read(b));return a;}
            case 14:{int n=size(b);align(b,4);float[] a=new float[n];for(int i=0;i<n;i++)a[i]=b.getFloat();return a;}
            default:throw new IllegalArgumentException("codec tag "+t);
        }
    }
    static void align(ByteBuffer b,int n){b.position((b.position()+n-1)&-n);}
    static int size(ByteBuffer b){int n=b.get()&255;return n<254?n:n==254?b.getChar():b.getInt();}
    static void number(ByteArrayOutputStream b,long n,int bytes){for(int i=0;i<bytes;i++)b.write((int)(n>>>(i*8))&255);}
    static void size(ByteArrayOutputStream b,int n){if(n<254)b.write(n);else if(n<65536){b.write(254);number(b,n,2);}else{b.write(255);number(b,n,4);}}
    static void write(ByteArrayOutputStream b,Object value){
        if(value==null){b.write(0);}else if(value instanceof Boolean){b.write((Boolean)value?1:2);}
        else if(value instanceof Integer){b.write(3);number(b,((Number)value).longValue(),4);}
        else if(value instanceof Long){b.write(4);number(b,((Number)value).longValue(),8);}
        else if(value instanceof Number){b.write(6);while(b.size()%8!=0)b.write(0);number(b,Double.doubleToLongBits(((Number)value).doubleValue()),8);}
        else if(value instanceof String){b.write(7);byte[]a=((String)value).getBytes(StandardCharsets.UTF_8);size(b,a.length);b.write(a,0,a.length);}
        else if(value instanceof byte[]){byte[]a=(byte[])value;b.write(8);size(b,a.length);b.write(a,0,a.length);}
        else if(value instanceof Map){Map<?,?>m=(Map<?,?>)value;b.write(13);size(b,m.size());for(Map.Entry<?,?>e:m.entrySet()){write(b,e.getKey());write(b,e.getValue());}}
        else if(value instanceof List){List<?>l=(List<?>)value;b.write(12);size(b,l.size());for(Object o:l)write(b,o);}
        else throw new IllegalArgumentException("Unsupported codec value "+value.getClass());
    }
    static byte[] encode(Object value,boolean envelope){ByteArrayOutputStream b=new ByteArrayOutputStream();if(envelope)b.write(0);write(b,value);return b.toByteArray();}
    static ByteBuffer buffer(byte[] data){return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);}
}
