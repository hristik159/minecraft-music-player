import dev.krys.blockradio.RadioMath;
public class DistanceCheck {
 public static void main(String[] args){
  if(RadioMath.attenuation(0)!=1)throw new AssertionError("At block");
  if(RadioMath.attenuation(16)!=.25)throw new AssertionError("Half radius");
  for(double d:new double[]{32,33,100,1000000})
   if(RadioMath.attenuation(d)!=0)throw new AssertionError("Audible outside radius");
  double previous=1;
  for(double d=0;d<=40;d+=.1){
   double value=RadioMath.attenuation(d);
   if(value>previous||value<0||value>1)throw new AssertionError("Non-monotonic gain");
   previous=value;
  }
  System.out.println("Distance checks passed: near, midpoint, boundary, far, monotonic falloff.");
 }
}

