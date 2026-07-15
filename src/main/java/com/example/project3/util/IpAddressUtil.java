package com.example.project3.util;

import java.util.regex.Pattern;

public class IpAddressUtil {

    // Geçerli bir IPv4 formatı için Regex
    private static final String IPV4_REGEX =
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$";
    private static final Pattern IPV4_PATTERN = Pattern.compile(IPV4_REGEX);

    // 1. IP adresinin geçerli bir IPv4 olup olmadığını kontrol eder
    public static boolean isValidIpv4(String ip) {
        if (ip == null || ip.trim().isEmpty()) return false;
        return IPV4_PATTERN.matcher(ip.trim()).matches();
    }

    // 2. İki IP'yi karşılaştırmak için matematiksel karşılığına çevirir
    public static long ipToLong(String ipAddress) {
        String[] parts = ipAddress.trim().split("\\.");
        long result = 0;
        for (int i = 0; i < parts.length; i++) {
            int power = 3 - i;
            long ip = Long.parseLong(parts[i]);
            result += ip * Math.pow(256, power);
        }
        return result;
    }

    // 3. Aralık (Range) kontrolü: Bitiş, Başlangıçtan küçük olamaz!
    public static boolean isValidRange(String startIp, String endIp) {
        if (!isValidIpv4(startIp) || !isValidIpv4(endIp)) return false;
        return ipToLong(startIp) <= ipToLong(endIp); // Bitiş, başlangıca eşit veya büyük olmalı
    }

    // 4. CIDR Doğrulama (Örn: 192.168.1.0/24)
    public static boolean isValidCidr(String cidr) {
        if (cidr == null || !cidr.contains("/")) return false;
        String[] parts = cidr.split("/");
        if (parts.length != 2) return false;

        String ip = parts[0];
        try {
            int prefix = Integer.parseInt(parts[1]);
            return isValidIpv4(ip) && prefix >= 0 && prefix <= 32;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // 5. CIDR'ı alıp [Başlangıç IP, Bitiş IP] şeklinde dizi döndürür
    public static String[] getRangeFromCidr(String cidr) {
        if (!isValidCidr(cidr)) return null;

        String[] parts = cidr.split("/");
        String ip = parts[0];
        int prefix = Integer.parseInt(parts[1]);

        int mask = 0xffffffff << (32 - prefix);
        int ipInt = ipToInt(ip);

        int startIpInt = ipInt & mask;
        int endIpInt = startIpInt | ~mask;

        return new String[]{intToIp(startIpInt), intToIp(endIpInt)};
    }

    private static int ipToInt(String ipAddress) {
        String[] parts = ipAddress.split("\\.");
        return (Integer.parseInt(parts[0]) << 24) |
                (Integer.parseInt(parts[1]) << 16) |
                (Integer.parseInt(parts[2]) << 8)  |
                (Integer.parseInt(parts[3]));
    }

    private static String intToIp(int ipInt) {
        return String.format("%d.%d.%d.%d",
                (ipInt >> 24) & 0xff, (ipInt >> 16) & 0xff, (ipInt >> 8) & 0xff, ipInt & 0xff);
    }
}