package dev.krys.blockradio;

/** Distance gain shared by the player and the standalone regression checks. */
public final class RadioMath {
 private RadioMath(){}
 public static double attenuation(double distance){
  double linear=Math.max(0,Math.min(1,1-distance/32));
  return linear*linear;
 }
}

