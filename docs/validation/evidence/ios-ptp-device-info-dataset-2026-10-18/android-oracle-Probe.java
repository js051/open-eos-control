import dev.openeos.control.data.*;
import java.nio.file.*;
import java.util.*;
public class Probe {
 static String utf(String s) {StringBuilder b=new StringBuilder();for(char c:s.toCharArray())b.append(String.format("%04x",(int)c));return b.toString();}
 public static void main(String[] args)throws Exception {
 for(String line:Files.readAllLines(Path.of(args[0]))) {
 String[] p=line.split("\t",-1);System.out.print(p[0]+"\t");
 try{PtpDeviceInfo d=PtpDatasets.INSTANCE.deviceInfo(HexFormat.of().parseHex(p[1]));
 System.out.println("OK\t"+d.getStandardVersion()+"\t"+d.getVendorExtensionId()+"\t"+d.getVendorExtensionVersion()+"\t"+utf(d.getVendorExtensionDescription())+"\t"+d.getFunctionalMode()+"\t"+new TreeSet<>(d.getOperations())+"\t"+new TreeSet<>(d.getEvents())+"\t"+new TreeSet<>(d.getDeviceProperties())+"\t"+new TreeSet<>(d.getCaptureFormats())+"\t"+new TreeSet<>(d.getImageFormats())+"\t"+utf(d.getManufacturer())+"\t"+utf(d.getModel())+"\t"+utf(d.getDeviceVersion())+"\t"+utf(d.getSerialNumber()));
 }catch(PtpProtocolException e){System.out.println("REJECT");}
 }
 }
}
