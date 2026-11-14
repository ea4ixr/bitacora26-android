package es.bitacora26.movil;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Base de datos del móvil. Mismos nombres de campos que Bitácora 26 de PC para poder sincronizar después.
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
        String nombre;
        int contactos;
    }

    Db(Context c) { super(c, "bitacora26_movil.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE qsos (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT, indicativo TEXT, fecha TEXT, hora TEXT, "
                + "banda TEXT, modo TEXT, rst_s TEXT, rst_r TEXT, nombre TEXT, qth TEXT, grid TEXT, comentario TEXT, "
                + "actividad_numeral INTEGER DEFAULT 0, actividad TEXT DEFAULT '', operador TEXT, creado INTEGER)");
        db.execSQL("CREATE TABLE actividades (numeral INTEGER PRIMARY KEY, nombre TEXT, uuid TEXT, creado INTEGER)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int a, int b) { }

    long guardar(Qso q) {
        ContentValues v = new ContentValues();
        v.put("uuid", UUID.randomUUID().toString().replace("-", ""));
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
        v.put("operador", q.operador);
        v.put("creado", System.currentTimeMillis());
        return getWritableDatabase().insert("qsos", null, v);
    }

    void borrar(long id) { getWritableDatabase().delete("qsos", "id = ?", new String[]{String.valueOf(id)}); }

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
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id, uuid, indicativo, fecha, hora, banda, modo, rst_s, rst_r, nombre, qth, grid, "
                + "comentario, actividad_numeral, actividad, operador FROM qsos WHERE " + w + " ORDER BY creado DESC LIMIT " + limite, a.toArray(new String[0]))) {
            while (c.moveToNext()) r.add(leer(c));
        }
        return r;
    }

    List<Qso> todos() {
        List<Qso> r = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id, uuid, indicativo, fecha, hora, banda, modo, rst_s, rst_r, nombre, qth, grid, "
                + "comentario, actividad_numeral, actividad, operador FROM qsos ORDER BY creado ASC", null)) {
            while (c.moveToNext()) r.add(leer(c));
        }
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
                + "(COALESCE(nombre,'') <> '' OR COALESCE(qth,'') <> '') ORDER BY creado DESC LIMIT 1", new String[]{indicativo})) {
            if (c.moveToFirst()) return new String[]{c.getString(0), c.getString(1), c.getString(2)};
        }
        return null;
    }

    List<Actividad> actividades() {
        List<Actividad> r = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT a.numeral, a.nombre, (SELECT COUNT(*) FROM qsos q WHERE q.actividad_numeral = a.numeral) "
                + "FROM actividades a ORDER BY a.numeral DESC", null)) {
            while (c.moveToNext()) { Actividad x = new Actividad(); x.numeral = c.getInt(0); x.nombre = c.getString(1); x.contactos = c.getInt(2); r.add(x); }
        }
        return r;
    }

    int crearActividad(String nombre) {
        int n = 1;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COALESCE(MAX(numeral), 0) + 1 FROM actividades", null)) { if (c.moveToFirst()) n = c.getInt(0); }
        ContentValues v = new ContentValues();
        v.put("numeral", n);
        v.put("nombre", nombre);
        v.put("uuid", UUID.randomUUID().toString().replace("-", ""));
        v.put("creado", System.currentTimeMillis());
        getWritableDatabase().insert("actividades", null, v);
        return n;
    }

    /** Borra la actividad; sus contactos se quedan en el LOG (como en el PC). */
    void borrarActividad(int numeral) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE qsos SET actividad_numeral = 0 WHERE actividad_numeral = ?", new Object[]{numeral});
        db.delete("actividades", "numeral = ?", new String[]{String.valueOf(numeral)});
    }

    String nombreActividad(int numeral) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT nombre FROM actividades WHERE numeral = ?", new String[]{String.valueOf(numeral)})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }
}
