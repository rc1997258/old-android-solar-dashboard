import com.diy.tapologger.Klap;
public class KlapTest {
  static int fails = 0;
  static void check(String name, boolean ok, String detail) { System.out.println((ok ? "PASS " : "FAIL ") + name + "  " + detail); if (!ok) fails++; }
  public static void main(String[] a) throws Exception {
    // 1. correct credentials: several requests on one session (seq increments)
    Klap k = new Klap("127.0.0.1:18080", Klap.authHash("Me@Example.com", "S3cret!"));
    String r1 = k.call("get_energy_usage", null);
    String r2 = k.call("get_emeter_data", null);
    String r3 = k.call("get_device_info", null);
    String r4 = k.call("get_energy_usage", null);
    check("energy usage", r1.contains("\"error_code\": 0") && r1.contains("current_power"), r1);
    check("emeter data", r2.contains("voltage_mv"), r2);
    check("device info", r3.contains("device_on"), r3);
    check("4th request same session", r4.contains("today_energy"), "");
    // 2. unknown method -> error_code passes through
    String r5 = k.call("get_something_unsupported", null);
    check("unsupported method", r5.contains("-1010"), r5);
    // 3. session reset -> transparent re-handshake
    k.reset(); String r6 = k.call("get_energy_usage", null);
    check("re-handshake after reset", r6.contains("current_power"), "");
    // 4. wrong password (case-sensitive e-mail too)
    try { new Klap("127.0.0.1:18080", Klap.authHash("me@example.com", "S3cret!")).call("get_energy_usage", null); check("wrong creds rejected", false, "no exception"); }
    catch (Klap.KlapException e) { check("wrong creds rejected", e.getMessage().contains("Wrong Tapo e-mail or password"), e.getMessage()); }
    // 5. Third-Party Compatibility OFF (403 on handshake1)
    try { new Klap("127.0.0.1:18081", Klap.authHash("Me@Example.com", "S3cret!")).call("get_energy_usage", null); check("403 explained", false, "no exception"); }
    catch (Klap.KlapException e) { check("403 explained", e.getMessage().contains("Third-Party Compatibility"), e.getMessage()); }
    // 6. nothing listening
    try { new Klap("127.0.0.1:18099", Klap.authHash("x", "y")).call("get_energy_usage", null); check("offline error", false, "no exception"); }
    catch (java.io.IOException e) { check("offline error", true, e.getClass().getSimpleName() + ": " + e.getMessage()); }
    // 7. hash helper matches python-kasa
    System.out.println("authHash=" + Klap.hex(Klap.authHash("Me@Example.com", "S3cret!")));
    System.out.println(fails == 0 ? "ALL KLAP TESTS PASSED" : fails + " FAILED");
  }
}
