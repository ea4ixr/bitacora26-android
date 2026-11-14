package es.bitacora26.movil;

import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Conexión con la cuenta de Bitácora 26 (bitacora26.es) y el «buzón» con el PC.
 * El buzón solo guarda lo que está en camino: el servidor lo borra en cuanto el otro lo recoge (o a los 30 días).
 * Las líneas son las mismas que usa el PC entre sus ordenadores (clave=valor&clave=valor).
 */
final class Nube {

    static final String SERVIDOR = "https://bitacora26.es/api/cuentas";
    private static final String ALIAS = "bitacora26_movil";

    private final SharedPreferences prefs;
    private final Db db;

    Nube(SharedPreferences prefs, Db db) { this.prefs = prefs; this.db = db; }

    String cuenta() { return prefs.getString("cuenta", ""); }
    boolean conectada() { return !cuenta().isEmpty(); }

    /** Marca de este móvil en las líneas (como la de cada ordenador en el PC). */
    String marca() {
        String m = prefs.getString("marca", "");
        if (m.isEmpty()) {
            m = "movil" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 11);
            prefs.edit().putString("marca", m).apply();
        }
        return m;
    }

    // ------------------------------------------------------------------ llave del móvil (no sale nunca del móvil)

    private KeyStore almacen() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            g.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build());
            g.generateKeyPair();
        }
        return ks;
    }

    String pub() throws Exception {
        PublicKey k = almacen().getCertificate(ALIAS).getPublicKey();
        return Base64.encodeToString(k.getEncoded(), Base64.NO_WRAP);
    }

    String firmar(String texto) throws Exception {
        PrivateKey k = (PrivateKey) almacen().getKey(ALIAS, null);
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(k);
        s.update(texto.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(s.sign(), Base64.NO_WRAP);
    }

    private long ultimoT = 0;

    private synchronized long t() {
        ultimoT = Math.max(System.currentTimeMillis(), Math.max(ultimoT + 1, prefs.getLong("ultimoT", 0) + 1));
        prefs.edit().putLong("ultimoT", ultimoT).apply();
        return ultimoT;
    }

    // ------------------------------------------------------------------ peticiones

    static JSONObject post(String ruta, JSONObject cuerpo) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(SERVIDOR + ruta).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("User-Agent", "Bitacora26-movil/" + MainActivity.VERSION);
        try (OutputStream o = c.getOutputStream()) { o.write(cuerpo.toString().getBytes(StandardCharsets.UTF_8)); }
        int codigo = c.getResponseCode();
        InputStream in = codigo >= 400 ? c.getErrorStream() : c.getInputStream();
        String txt = in == null ? "{}" : new String(leerTodo(in), StandardCharsets.UTF_8);
        JSONObject r;
        try { r = new JSONObject(txt); } catch (Exception e) { r = new JSONObject(); }
        r.put("_codigo", codigo);
        return r;
    }

    private static byte[] leerTodo(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }

    static String error(JSONObject r) {
        String e = r.optString("error", "");
        return e.isEmpty() ? "No hay conexión con bitacora26.es" : e;
    }

    private JSONObject firmado(String accion, JSONObject datos) throws Exception {
        String ind = cuenta();
        long t = t();
        String d = datos.toString();
        JSONObject p = new JSONObject();
        p.put("ind", ind);
        p.put("pub", pub());
        p.put("t", t);
        p.put("accion", accion);
        p.put("datos", d);
        p.put("s", firmar("B26\n" + accion + "\n" + ind + "\n" + t + "\n" + d));
        return post("/firmado", p);
    }

    // ------------------------------------------------------------------ conectar el móvil a la cuenta (como vincular un ordenador)

    /** Paso 1: indicativo y contraseña. El servidor manda un código al correo de la cuenta. Devuelve el correo (oculto) o lanza el error. */
    String vincular(String quien, String clave) throws Exception {
        long t = t();
        JSONObject p = new JSONObject();
        p.put("ind", quien);
        p.put("clave", clave);
        p.put("pub", pub());
        p.put("equipo", "MOVIL Android " + Build.MANUFACTURER + " " + Build.MODEL);
        p.put("t", t);
        p.put("s", firmar("VINCULAR\n" + quien + "\n" + t));
        JSONObject r = post("/vincular", p);
        if (r.getInt("_codigo") != 200) throw new Exception(error(r));
        return r.optString("correo", "tu correo");
    }

    /** Paso 2: el código del correo. Queda conectado y pide al PC el LOG entero. Devuelve el indicativo de la cuenta. */
    String vincularCodigo(String quien, String codigo) throws Exception {
        long t = t();
        JSONObject p = new JSONObject();
        p.put("ind", quien);
        p.put("pub", pub());
        p.put("codigo", codigo);
        p.put("t", t);
        p.put("s", firmar("VINCULAR_CODIGO\n" + quien + "\n" + codigo + "\n" + t));
        JSONObject r = post("/vincular_codigo", p);
        if (r.getInt("_codigo") != 200) throw new Exception(error(r));
        JSONObject per = r.getJSONObject("perfil");
        String id = per.optString("hf_id", "");
        String ind = per.optString("hf", "");
        if (id.isEmpty()) { id = per.optString("cb_id", ""); ind = per.optString("cb", ""); }
        prefs.edit().putString("cuenta", id).apply();
        firmado("buzon_pedir_completo", new JSONObject());
        return ind.toUpperCase();
    }

    void desconectar() { prefs.edit().remove("cuenta").apply(); }

    // ------------------------------------------------------------------ líneas (mismo formato que el PC)

    static String codificar(SortedMap<String, String> m) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
        }
        return sb.toString();
    }

    static SortedMap<String, String> decodificar(String s) throws Exception {
        SortedMap<String, String> m = new TreeMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String par : s.split("&")) {
            int k = par.indexOf('=');
            if (k < 0) continue;
            m.put(URLDecoder.decode(par.substring(0, k), "UTF-8"), URLDecoder.decode(par.substring(k + 1), "UTF-8"));
        }
        return m;
    }

    private String linea(String tipo, String clave, String datos, String op) throws Exception {
        SortedMap<String, String> l = new TreeMap<>();
        l.put("v", "1"); l.put("t", tipo); l.put("k", clave); l.put("f", String.valueOf(System.currentTimeMillis()));
        l.put("del", datos == null ? "1" : "0"); l.put("d", datos == null ? "" : datos); l.put("dev", marca()); l.put("op", op);
        return codificar(l);
    }

    /** Apunta para mandar al PC un contacto nuevo (o borrado, con q.indicativo == null). */
    void encolarQso(Db.Qso q, String uuid, String op) {
        if (!conectada()) return;
        try {
            String datos = null;
            if (q != null) {
                SortedMap<String, String> m = new TreeMap<>();
                m.put("fecha", q.fecha); m.put("hora", q.hora); m.put("ind", q.indicativo); m.put("banda", q.banda); m.put("modo", q.modo);
                m.put("ftx", ""); m.put("frx", ""); m.put("rsts", nz(q.rstS)); m.put("rstr", nz(q.rstR)); m.put("nom", nz(q.nombre));
                m.put("qth", nz(q.qth)); m.put("grid", nz(q.grid)); m.put("puntos", ""); m.put("num", ""); m.put("est", op);
                m.put("act", nz(db.uuidActividad(q.actividadNumeral))); m.put("sub", "0");
                if (q.comentario != null && !q.comentario.isEmpty()) m.put("com", q.comentario);
                datos = codificar(m);
            }
            db.encolar(linea("QSO", uuid, datos, op));
        } catch (Exception ignored) { }
    }

    void encolarActividad(String uuid, String nombre, String op) {
        if (!conectada()) return;
        try {
            String datos = null;
            if (nombre != null) {
                SortedMap<String, String> m = new TreeMap<>();
                m.put("nombre", nombre);
                for (String k : new String[]{"tipo", "creador", "bronce", "plata", "oro", "cat6", "cat6u", "fecha", "banda", "modo", "indrc", "puntos"}) m.put(k, "");
                m.put("tipo", "SIMPLE");
                m.put("creador", op);
                m.put("fecha", MainActivity.ahoraUtc("dd/MM/yyyy"));
                datos = codificar(m);
            }
            db.encolar(linea("ACT", uuid, datos, op));
        } catch (Exception ignored) { }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    // ------------------------------------------------------------------ sincronizar

    static String comprimir(String s) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bo)) { gz.write(s.getBytes(StandardCharsets.UTF_8)); }
        return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
    }

    static String descomprimir(String b64) throws Exception {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(Base64.decode(b64, Base64.DEFAULT)))) {
            return new String(leerTodo(gz), StandardCharsets.UTF_8);
        }
    }

    /** Manda lo pendiente y recoge lo que haya dejado el PC. Devuelve {enviados, recibidos}. */
    int[] sincronizar(String op) throws Exception {
        if (!conectada()) return new int[]{0, 0};
        int enviados = 0, recibidos = 0;
        // 1. Mandar
        List<Object[]> pend = db.pendientes();
        if (!pend.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            List<Long> ids = new ArrayList<>();
            for (Object[] p : pend) { sb.append((String) p[1]).append('\n'); ids.add((Long) p[0]); }
            JSONObject d = new JSONObject();
            d.put("lote", comprimir(sb.toString()));
            JSONObject r = firmado("buzon_enviar", d);
            if (r.getInt("_codigo") != 200) throw new Exception(error(r));
            db.quitarPendientes(ids);
            enviados = ids.size();
        }
        // 2. Recoger
        for (int vuelta = 0; vuelta < 50; vuelta++) {
            JSONObject r = firmado("buzon_recoger", new JSONObject());
            if (r.getInt("_codigo") != 200) throw new Exception(error(r));
            JSONArray lotes = r.optJSONArray("lotes"), ids = r.optJSONArray("ids");
            if (lotes == null || ids == null || lotes.length() == 0) break;
            List<String> lineas = new ArrayList<>();
            for (int i = 0; i < lotes.length(); i++)
                for (String l : descomprimir(lotes.getString(i)).split("\n")) if (!l.trim().isEmpty()) lineas.add(l);
            recibidos += db.aplicarLineas(lineas, op, marca());
            JSONObject b = new JSONObject();
            b.put("ids", ids);
            firmado("buzon_borrar", b);
            if (r.optInt("quedan", 0) <= 0) break;
        }
        prefs.edit().putLong("ultimaSync", System.currentTimeMillis()).apply();
        return new int[]{enviados, recibidos};
    }
}
