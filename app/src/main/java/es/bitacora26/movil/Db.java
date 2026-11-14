package es.bitacora26.movil;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.UUID;

/**
 * Base de datos del móvil. Mismos nombres de campos que Bitácora 26 de PC para poder sincronizar.
 * La fecha y la hora se guardan SIEMPRE en UTC (dd/MM/yyyy y HH:mm), como en el PC.
 */
final class Db extends SQLiteOpenHelper {

    static final class Qso {
        long id;
        String uuid, indicativo, fecha, hora, banda, modo, rstS, rstR, nombre, qth, grid, comentario, actividad, operador;
        int actividadNumeral;
    }

    static final class Actividad {
        int numeral;
        String nombre, uuid;
        int contactos;
    }

    Db(Context c) { super(c, "bitacora26_movil.db", null, 2); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE qsos (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT, indicativo TEXT, fecha TEXT, hora TEXT, "
                + "banda TEXT, modo TEXT, rst_s TEXT, rst_r TEXT, nombre TEXT, qth TEXT, grid TEXT, comentario TEXT, "
                + "actividad_numeral INTEGER DEFAULT 0, actividad TEXT DEFAULT '', operador TEXT, creado INTEGER)");
        db.execSQL("CREATE TABLE actividades (numeral INTEGER PRIMARY KEY, nombre TEXT, uuid TEXT, creado INTEGER)");
        onUpgrade(db, 1, 2);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int antes, int ahora) {
        if (antes < 2) {
            db.execSQL("ALTER TABLE qsos ADD COLUMN act_uuid TEXT DEFAULT ''");
            db.execSQL("ALTER TABLE qsos ADD COLUMN f INTEGER DEFAULT 0");
            db.execSQL("CREATE TABLE IF NOT EXISTS pendientes (id INTEGER PRIMARY KEY AUTOINCREMENT, linea TEXT)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS qsos_uuid ON qsos (uuid)");
            db.execSQL("UPDATE qsos SET act_uuid = COALESCE((SELECT a.uuid FROM actividades a WHERE a.numeral = qsos.actividad_numeral), '') "
                    + "WHERE actividad_numeral > 0");
        }
    }

    static String nuevoUuid() { return UUID.randomUUID().toString().replace("-", ""); }

    /** Guarda un contacto nuevo. Devuelve su uuid. */
    String guardar(Qso q) {
        String uuid = nuevoUuid();
        ContentValues v = new ContentValues();
        v.put("uuid", uuid);
        v.put("indicativo", q.indicativo);
        v.put("fecha", q.fecha);
        v.put("hora", q.hora);
        v.put("banda", q.banda);
        v.put("modo", q.modo);
        v.put("rst_s", q.rstS);
        v.put("rst_r", q.rstR);
        v.put("nombre", q.nombre);
        v.put("qth", q.qth);
        v.put("grid", q.grid);
        v.put("comentario", q.comentario);
        v.put("actividad_numeral", q.actividadNumeral);
        v.put("actividad", q.actividad == null ? "" : q.actividad);
        v.put("act_uuid", q.actividadNumeral > 0 ? uuidActividad(q.actividadNumeral) : "");
        v.put("operador", q.operador);
        v.put("creado", System.currentTimeMillis());
        v.put("f", System.currentTimeMillis());
        getWritableDatabase().insert("qsos", null, v);
        return uuid;
    }

    void borrar(long id) { getWritableDatabase().delete("qsos", "id = ?", new String[]{String.valueOf(id)}); }

    private static final String CAMPOS = "id, uuid, indicativo, fecha, hora, banda, modo, rst_s, rst_r, nombre, qth, grid, "
            + "comentario, actividad_numeral, actividad, operador";
    // Los más recientes primero: por fecha y hora (UTC), para que lo que llega del PC salga en su sitio
    private static final String ORDEN = " ORDER BY substr(fecha,7,4) DESC, substr(fecha,4,2) DESC, substr(fecha,1,2) DESC, hora DESC, id DESC";

    /** filtro: TODOS, HF, CB o HOY. busca: trozo de indicativo. actividad: 0 = todas. */
    List<Qso> qsos(String filtro, String busca, int actividad, int limite, String hoyUtc) {
        StringBuilder w = new StringBuilder("1=1");
        List<String> a = new ArrayList<>();
        if ("CB".equals(filtro)) w.append(" AND banda = 'CB'");
        else if ("HF".equals(filtro)) w.append(" AND COALESCE(banda,'') <> 'CB'");
        else if ("HOY".equals(filtro)) { w.append(" AND fecha = ?"); a.add(hoyUtc); }
        if (busca != null && !busca.trim().isEmpty()) { w.append(" AND indicativo LIKE ?"); a.add("%" + busca.trim().toUpperCase() + "%"); }
        if (actividad > 0) { w.append(" AND actividad_numeral = ?"); a.add(String.valueOf(actividad)); }
        List<Qso> r = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + CAMPOS + " FROM qsos WHERE " + w + ORDEN + " LIMIT " + limite, a.toArray(new String[0]))) {
            while (c.moveToNext()) r.add(leer(c));
        }
        return r;
    }

    List<Qso> todos() {
        List<Qso> r = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + CAMPOS + " FROM qsos" + ORDEN, null)) {
            while (c.moveToNext()) r.add(leer(c));
        }
        Collections.reverse(r);
        return r;
    }

    private static Qso leer(Cursor c) {
        Qso q = new Qso();
        q.id = c.getLong(0); q.uuid = c.getString(1); q.indicativo = c.getString(2); q.fecha = c.getString(3); q.hora = c.getString(4);
        q.banda = c.getString(5); q.modo = c.getString(6); q.rstS = c.getString(7); q.rstR = c.getString(8); q.nombre = c.getString(9);
        q.qth = c.getString(10); q.grid = c.getString(11); q.comentario = c.getString(12); q.actividadNumeral = c.getInt(13);
        q.actividad = c.getString(14); q.operador = c.getString(15);
        return q;
    }

    int contar(String where, String... args) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM qsos WHERE " + where, args)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** Contactos anteriores con ese indicativo: "40m SSB, 20m FT8" (para avisar de "ya trabajado"). */
    String yaTrabajado(String indicativo) {
        StringBuilder sb = new StringBuilder();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT DISTINCT banda, modo FROM qsos WHERE indicativo = ? LIMIT 6", new String[]{indicativo})) {
            while (c.moveToNext()) { if (sb.length() > 0) sb.append(", "); sb.append(c.getString(0)).append(' ').append(c.getString(1)); }
        }
        return sb.toString();
    }

    /** Nombre, QTH y locator de un contacto anterior con ese indicativo (datos propios, como el PC). */
    String[] datosDe(String indicativo) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT nombre, qth, grid FROM qsos WHERE indicativo = ? AND "
                + "(COALESCE(nombre,'') <> '' OR COALESCE(qth,'') <> '') ORDER BY id DESC LIMIT 1", new String[]{indicativo})) {
            if (c.moveToFirst()) return new String[]{c.getString(0), c.getString(1), c.getString(2)};
        }
        return null;
    }

    List<Actividad> actividades() {
        List<Actividad> r = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT a.numeral, a.nombre, (SELECT COUNT(*) FROM qsos q WHERE q.actividad_numeral = a.numeral), a.uuid "
                + "FROM actividades a ORDER BY a.numeral DESC", null)) {
            while (c.moveToNext()) {
                Actividad x = new Actividad(); x.numeral = c.getInt(0); x.nombre = c.getString(1); x.contactos = c.getInt(2); x.uuid = c.getString(3);
                r.add(x);
            }
        }
        return r;
    }

    private int siguienteNumeral() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COALESCE(MAX(numeral), 0) + 1 FROM actividades", null)) { return c.moveToFirst() ? c.getInt(0) : 1; }
    }

    /** Crea una actividad. Devuelve {numeral, uuid}. */
    Object[] crearActividad(String nombre) {
        int n = siguienteNumeral();
        String uuid = nuevoUuid();
        ContentValues v = new ContentValues();
        v.put("numeral", n);
        v.put("nombre", nombre);
        v.put("uuid", uuid);
        v.put("creado", System.currentTimeMillis());
        getWritableDatabase().insert("actividades", null, v);
        return new Object[]{n, uuid};
    }

    /** Borra la actividad; sus contactos se quedan en el LOG (como en el PC). */
    void borrarActividad(int numeral) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE qsos SET actividad_numeral = 0, act_uuid = '' WHERE actividad_numeral = ?", new Object[]{numeral});
        db.delete("actividades", "numeral = ?", new String[]{String.valueOf(numeral)});
    }

    String nombreActividad(int numeral) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT nombre FROM actividades WHERE numeral = ?", new String[]{String.valueOf(numeral)})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    String uuidActividad(int numeral) {
        if (numeral <= 0) return "";
        try (Cursor c = getReadableDatabase().rawQuery("SELECT uuid FROM actividades WHERE numeral = ?", new String[]{String.valueOf(numeral)})) {
            return c.moveToFirst() && c.getString(0) != null ? c.getString(0) : "";
        }
    }

    // ------------------------------------------------------------------ cola de lo que hay que mandar al PC

    void encolar(String linea) {
        ContentValues v = new ContentValues();
        v.put("linea", linea);
        getWritableDatabase().insert("pendientes", null, v);
    }

    List<Object[]> pendientes() {
        List<Object[]> r = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id, linea FROM pendientes ORDER BY id LIMIT 5000", null)) {
            while (c.moveToNext()) r.add(new Object[]{c.getLong(0), c.getString(1)});
        }
        return r;
    }

    int numPendientes() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM pendientes", null)) { return c.moveToFirst() ? c.getInt(0) : 0; }
    }

    void quitarPendientes(List<Long> ids) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Long id : ids) db.delete("pendientes", "id = ?", new String[]{String.valueOf(id)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ------------------------------------------------------------------ lo que llega del PC

    /** Aplica las líneas recibidas (las del operador de este móvil). Devuelve cuántas se han aplicado. */
    int aplicarLineas(List<String> lineas, String op, String miMarca) throws Exception {
        List<SortedMap<String, String>> ls = new ArrayList<>();
        for (String l : lineas) ls.add(Nube.decodificar(l));
        // En el orden en que se hicieron, y las actividades antes que sus contactos
        Collections.sort(ls, (a, b) -> {
            int ta = "ACT".equals(a.get("t")) ? 0 : 1, tb = "ACT".equals(b.get("t")) ? 0 : 1;
            if (ta != tb) return ta - tb;
            return Long.compare(largo(a.get("f")), largo(b.get("f")));
        });
        int n = 0;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (SortedMap<String, String> m : ls) {
                if (!op.equalsIgnoreCase(m.getOrDefault("op", ""))) continue;   // de otro operador
                if (miMarca.equals(m.getOrDefault("dev", ""))) continue;        // es mía
                String tipo = m.getOrDefault("t", ""), k = m.getOrDefault("k", "");
                if (k.isEmpty()) continue;
                boolean borrar = "1".equals(m.get("del"));
                long f = largo(m.get("f"));
                SortedMap<String, String> x = Nube.decodificar(m.getOrDefault("d", ""));
                if ("QSO".equals(tipo)) { if (aplicarQso(db, k, borrar, f, x)) n++; }
                else if ("ACT".equals(tipo)) { aplicarActividad(db, k, borrar, x); n++; }
            }
            // Contactos que llegaron antes que su actividad
            db.execSQL("UPDATE qsos SET actividad_numeral = (SELECT a.numeral FROM actividades a WHERE a.uuid = qsos.act_uuid), "
                    + "actividad = (SELECT a.nombre FROM actividades a WHERE a.uuid = qsos.act_uuid) "
                    + "WHERE COALESCE(act_uuid,'') <> '' AND EXISTS (SELECT 1 FROM actividades a WHERE a.uuid = qsos.act_uuid)");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return n;
    }

    private static long largo(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return 0; }
    }

    private boolean aplicarQso(SQLiteDatabase db, String uuid, boolean borrar, long f, SortedMap<String, String> x) {
        long fLocal = -1;
        try (Cursor c = db.rawQuery("SELECT COALESCE(f,0) FROM qsos WHERE uuid = ?", new String[]{uuid})) { if (c.moveToFirst()) fLocal = c.getLong(0); }
        if (borrar) {
            db.delete("qsos", "uuid = ?", new String[]{uuid});
            return fLocal >= 0;
        }
        if (fLocal > f) return false;   // aquí hay algo más nuevo
        ContentValues v = new ContentValues();
        v.put("indicativo", x.getOrDefault("ind", "").toUpperCase());
        v.put("fecha", x.getOrDefault("fecha", ""));
        v.put("hora", x.getOrDefault("hora", ""));
        v.put("banda", x.getOrDefault("banda", ""));
        v.put("modo", x.getOrDefault("modo", ""));
        v.put("rst_s", x.getOrDefault("rsts", ""));
        v.put("rst_r", x.getOrDefault("rstr", ""));
        v.put("nombre", x.getOrDefault("nom", ""));
        v.put("qth", x.getOrDefault("qth", ""));
        v.put("grid", x.getOrDefault("grid", ""));
        v.put("comentario", x.getOrDefault("com", ""));
        String act = x.getOrDefault("act", "");
        v.put("act_uuid", act);
        int numeral = 0;
        String nombreAct = "";
        if (!act.isEmpty()) {
            try (Cursor c = db.rawQuery("SELECT numeral, nombre FROM actividades WHERE uuid = ?", new String[]{act})) {
                if (c.moveToFirst()) { numeral = c.getInt(0); nombreAct = c.getString(1); }
            }
        }
        v.put("actividad_numeral", numeral);
        v.put("actividad", nombreAct);
        v.put("operador", x.getOrDefault("est", ""));
        v.put("f", f);
        if (fLocal >= 0) db.update("qsos", v, "uuid = ?", new String[]{uuid});
        else {
            v.put("uuid", uuid);
            v.put("creado", System.currentTimeMillis());
            db.insert("qsos", null, v);
        }
        return true;
    }

    private void aplicarActividad(SQLiteDatabase db, String uuid, boolean borrar, SortedMap<String, String> x) {
        int numeral = -1;
        try (Cursor c = db.rawQuery("SELECT numeral FROM actividades WHERE uuid = ?", new String[]{uuid})) { if (c.moveToFirst()) numeral = c.getInt(0); }
        if (borrar) {
            if (numeral < 0) return;
            db.execSQL("UPDATE qsos SET actividad_numeral = 0, act_uuid = '' WHERE actividad_numeral = ?", new Object[]{numeral});
            db.delete("actividades", "numeral = ?", new String[]{String.valueOf(numeral)});
            return;
        }
        String nombre = x.getOrDefault("nombre", "ACTIVIDAD");
        if (numeral < 0) {
            int n;
            try (Cursor c = db.rawQuery("SELECT COALESCE(MAX(numeral), 0) + 1 FROM actividades", null)) { n = c.moveToFirst() ? c.getInt(0) : 1; }
            ContentValues v = new ContentValues();
            v.put("numeral", n);
            v.put("nombre", nombre);
            v.put("uuid", uuid);
            v.put("creado", System.currentTimeMillis());
            db.insert("actividades", null, v);
        } else {
            ContentValues v = new ContentValues();
            v.put("nombre", nombre);
            db.update("actividades", v, "uuid = ?", new String[]{uuid});
            db.execSQL("UPDATE qsos SET actividad = ? WHERE actividad_numeral = ?", new Object[]{nombre, numeral});
        }
    }
}
