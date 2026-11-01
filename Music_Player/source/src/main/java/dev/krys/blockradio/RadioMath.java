package dev.krys.blockradio;
public final class RadioMath {
 private RadioMath(){}
 public static double attenuation(double distance){
  double t=Math.max(0,Math.min(1,(distance-5)/25));
  double smooth=1-t*t*(3-2*t);return smooth*smooth;
 }
}
