package dev.weather.oneplus;

/** Run with the JDK; exercises ordering, placeholder frames and route escape. */
public final class WeatherStartupGateCheck {
    private static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args){
        int[][] orders={{0,1,2},{0,2,1},{1,0,2},{1,2,0},{2,0,1},{2,1,0}};
        for(int[] order:orders){
            WeatherStartupGate gate=new WeatherStartupGate();gate.expectWeather();
            for(int n=0;n<3;n++){
                switch(order[n]){case 0:gate.frame();break;case 1:gate.content();break;case 2:gate.weatherFrame();break;}
                check(gate.ready()==(n==2));
            }
            check(gate.scheduleReveal());check(!gate.scheduleReveal());
            gate.close();check(!gate.ready());check(!gate.scheduleReveal());
        }
        WeatherStartupGate ordinary=new WeatherStartupGate();
        ordinary.frame();ordinary.content();ordinary.expectWeather();
        check(!ordinary.scheduleReveal());ordinary.ordinaryPage();check(ordinary.scheduleReveal());
        WeatherStartupGate lateScene=new WeatherStartupGate();
        lateScene.frame();lateScene.content();check(lateScene.scheduleReveal());
        lateScene.expectWeather();check(!lateScene.ready());lateScene.retry();
        lateScene.weatherFrame();check(lateScene.scheduleReveal());
        WeatherStartupGate destroyed=new WeatherStartupGate();
        destroyed.close();destroyed.frame();destroyed.content();check(!destroyed.scheduleReveal());
        System.out.println("PASS: all signal permutations, non-weather route, late scene and cancellation");
    }
}
