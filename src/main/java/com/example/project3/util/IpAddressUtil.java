package com.example.project3.util;

public class IpAddressUtil {
    // Kusursuz IPv4 Regex Formatı
    private static final String IPV4_PATTERN =
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$";

    public static boolean isValidIpv4(String ip) {
        return ip != null && ip.matches(IPV4_PATTERN);
    }

    // IP adresini sayısal bir formata (Long) çevirir (Büyüklük/Küçüklük kıyaslaması için)
    public static long ipToLong(String ipAddress) {
        String[] ipAddressInArray = ipAddress.split("\\.");
        long result = 0;
        for (int i = 0; i < ipAddressInArray.length; i++) {
            int power = 3 - i;
            int ip = Integer.parseInt(ipAddressInArray[i]);
            result += ip * Math.pow(256, power);
        }
        return result;
    }
}