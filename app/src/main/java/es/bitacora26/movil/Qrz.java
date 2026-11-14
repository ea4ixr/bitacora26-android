package es.bitacora26.movil;

import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Búsqueda en QRZ.com (XML), igual que en el PC, con la cuenta de QRZ del usuario. Nunca para banda CB. */
final class Qrz {

    private final SharedPreferences prefs;
    private String sesion;

    Qrz(SharedPreferences prefs) { this.prefs = prefs; }

    boolean configurado() { return !prefs.getString("qrzUsuario", "").isEmpty() && !prefs.getString("qrzClave", "").isEmpty(); }

    /** {nombre, qth, grid} si el indicativo está en QRZ.com; null si no está (o no se ha podido consultar). Fuera del hilo de la pantalla. */
    String[] buscar(String call) {
        if (!configurado() || call == null || call.trim().isEmpty()) return null;
        try {
            for (int intento = 0; intento < 2; intento++) {
                if (sesion == null || sesion.isEmpty()) {
                    String xml = get("https://xmldata.qrz.com/xml/current/?username=" + URLEncoder.encode(prefs.getString("qrzUsuario", ""), "UTF-8")
                            + ";password=" + URLEncoder.encode(prefs.getString("qrzClave", ""), "UTF-8") + ";agent=Bitacora26-movil");
                    sesion = etiqueta(xml, "Key");
                    if (sesion.isEmpty()) return null;
                }
                String xml = get("https://xmldata.qrz.com/xml/current/?s=" + sesion + ";callsign=" + URLEncoder.encode(call.trim(), "UTF-8"));
                if (xml.contains("Session Timeout") || xml.contains("Invalid session key")) { sesion = null; continue; }
                if (!etiqueta(xml, "call").equalsIgnoreCase(call.trim())) return null;
                String nombre = (etiqueta(xml, "fname") + " " + etiqueta(xml, "name")).trim();
                String qth = etiqueta(xml, "qth");
                if (qth.isEmpty()) qth = etiqueta(xml, "addr2");
                return new String[]{nombre, qth, etiqueta(xml, "grid")};
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Comprueba el usuario y la contraseña de QRZ.com. Devuelve null si valen, o el motivo. */
    String probar() {
        sesion = null;
        try {
            String xml = get("https://xmldata.qrz.com/xml/current/?username=" + URLEncoder.encode(prefs.getString("qrzUsuario", ""), "UTF-8")
                    + ";password=" + URLEncoder.encode(prefs.getString("qrzClave", ""), "UTF-8") + ";agent=Bitacora26-movil");
            sesion = etiqueta(xml, "Key");
            if (!sesion.isEmpty()) return null;
            String e = etiqueta(xml, "Error");
            return e.isEmpty() ? "QRZ.com no ha contestado" : e;
        } catch (Exception e) {
            return "Sin conexión con QRZ.com";
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "Bitacora26-movil/" + MainActivity.VERSION);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String etiqueta(String xml, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">", Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1).trim() : "";
    }
}
