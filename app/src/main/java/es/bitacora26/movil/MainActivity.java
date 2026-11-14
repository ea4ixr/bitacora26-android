package es.bitacora26.movil;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Bitácora 26 móvil: LOG de bolsillo. Cuatro pantallas (Inicio, Contacto, LOG, Actividades) y una barra abajo.
 * Reglas del PC: hora guardada en UTC, indicativos en mayúsculas, RST solo números, operador en cada contacto.
 */
public class MainActivity extends Activity {

    static final String VERSION = "0.2";

    // Colores (los mismos que las pantallas de ejemplo)
    static final int FONDO = Color.rgb(20, 32, 51), TARJETA = Color.rgb(29, 43, 66), BORDE = Color.rgb(51, 68, 92),
            CAMPO = Color.rgb(14, 24, 40), TEXTO = Color.rgb(232, 238, 247), GRIS = Color.rgb(159, 176, 200),
            NARANJA = Color.rgb(255, 176, 46), VERDE = Color.rgb(61, 220, 106), OSCURO = Color.rgb(26, 19, 0);

    static final String[] BANDAS = {"160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "CB", "6m", "2m", "70cm"};
    static final String[] MODOS = {"SSB", "LSB", "USB", "AM", "FM", "CW", "FT8", "FT4"};

    private Db db;
    private SharedPreferences prefs;
    private Nube nube;
    private Qrz qrz;
    private final java.util.concurrent.ExecutorService fondo = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile String estadoSync = "";
    private LinearLayout contenido, barra;
    private String pantalla = "INICIO";

    // Estado del formulario de contacto
    private String bandaSel, modoSel;
    // Estado de la lista del LOG
    private String filtroLog = "TODOS", buscaLog = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        db = new Db(this);
        prefs = getSharedPreferences("bitacora26", MODE_PRIVATE);
        nube = new Nube(prefs, db);
        qrz = new Qrz(prefs);
        bandaSel = prefs.getString("banda", "40m");
        modoSel = prefs.getString("modo", "SSB");

        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setBackgroundColor(FONDO);
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        contenido = new LinearLayout(this);
        contenido.setOrientation(LinearLayout.VERTICAL);
        contenido.setPadding(dp(18), dp(18), dp(18), dp(18));
        sv.addView(contenido);
        raiz.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        barra = new LinearLayout(this);
        barra.setOrientation(LinearLayout.HORIZONTAL);
        barra.setBackgroundColor(Color.rgb(14, 24, 40));
        barra.setPadding(dp(4), dp(6), dp(4), dp(6));
        raiz.addView(barra, new LinearLayout.LayoutParams(-1, -2));
        setContentView(raiz);

        if (operador().isEmpty()) pedirIndicativo(true);
        mostrar("INICIO");
        sincronizar(false);
    }

    @Override protected void onResume() {
        super.onResume();
        if (nube != null && nube.conectada() && System.currentTimeMillis() - prefs.getLong("ultimaSync", 0) > 60_000) sincronizar(false);
    }

    /** Manda y recoge del buzón, en segundo plano. Con «avisar», dice el resultado aunque no haya nada. */
    void sincronizar(boolean avisar) {
        if (!nube.conectada()) {
            if (avisar) Toast.makeText(this, "Conecta antes tu cuenta de Bitácora 26 (Ajustes)", Toast.LENGTH_LONG).show();
            return;
        }
        estadoSync = "Sincronizando…";
        fondo.execute(() -> {
            String msg;
            boolean cambios = false;
            try {
                int[] r = nube.sincronizar(operador());
                cambios = r[1] > 0;
                String h = new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date());
                msg = "Sincronizado " + h + (r[0] > 0 ? " · enviados " + r[0] : "") + (r[1] > 0 ? " · recibidos " + r[1] : "");
            } catch (Exception e) {
                msg = "Sin sincronizar: " + e.getMessage();
            }
            String fm = msg;
            boolean refrescar = cambios;
            ui.post(() -> {
                estadoSync = fm;
                if (avisar) Toast.makeText(this, fm, Toast.LENGTH_SHORT).show();
                if (refrescar || "INICIO".equals(pantalla)) mostrar(pantalla);
            });
        });
    }

    @Override public void onBackPressed() {
        if (!"INICIO".equals(pantalla)) mostrar("INICIO");
        else super.onBackPressed();
    }

    // ------------------------------------------------------------------ utilidades

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    String operador() { return prefs.getString("operador", ""); }

    static String ahoraUtc(String patron) {
        SimpleDateFormat f = new SimpleDateFormat(patron, Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    /** La hora se guarda en UTC; si el usuario lo elige, se muestra en hora local. */
    String horaParaVer(String fecha, String hora) {
        if (!prefs.getBoolean("verLocal", false) || hora == null || fecha == null) return hora;
        try {
            SimpleDateFormat u = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ROOT);
            u.setTimeZone(TimeZone.getTimeZone("UTC"));
            SimpleDateFormat l = new SimpleDateFormat("HH:mm", Locale.ROOT);
            return l.format(u.parse(fecha + " " + hora));
        } catch (Exception e) {
            return hora;
        }
    }

    private GradientDrawable fondo(int color, int borde, int radio) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radio));
        if (borde != 0) g.setStroke(dp(1), borde);
        return g;
    }

    private TextView texto(String t, int sp, int color, boolean negrita) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (negrita) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private TextView rotulo(String t) {
        TextView v = texto(t, 12, GRIS, true);
        v.setLetterSpacing(0.06f);
        v.setPadding(0, dp(14), 0, dp(6));
        return v;
    }

    private Button boton(String t, int fondo, int letra) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextColor(letra);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(fondo(fondo, 0, 14));
        b.setMinHeight(dp(52));
        return b;
    }

    private EditText campo(String pista, boolean numerico, int sp) {
        EditText e = new EditText(this);
        e.setHint(pista);
        e.setHintTextColor(Color.rgb(110, 125, 150));
        e.setTextColor(Color.WHITE);
        e.setTextSize(sp);
        e.setSingleLine(true);
        e.setBackground(fondo(CAMPO, BORDE, 10));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        if (numerico) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        else e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        // Todo en mayúsculas, como en el PC
        if (!numerico) e.setFilters(new InputFilter[]{new InputFilter.AllCaps()});
        return e;
    }

    private LinearLayout tarjeta() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(fondo(TARJETA, BORDE, 14));
        l.setPadding(dp(14), dp(12), dp(14), dp(12));
        return l;
    }

    private LinearLayout.LayoutParams separado(int arriba) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(arriba);
        return p;
    }

    /** Fila de botones para elegir uno (banda o modo). */
    private LinearLayout fichas(String[] opciones, String elegido, java.util.function.Consumer<String> alElegir) {
        LinearLayout todo = new LinearLayout(this);
        todo.setOrientation(LinearLayout.VERTICAL);
        LinearLayout fila = null;
        for (int i = 0; i < opciones.length; i++) {
            if (i % 5 == 0) {
                fila = new LinearLayout(this);
                fila.setOrientation(LinearLayout.HORIZONTAL);
                todo.addView(fila, separado(i == 0 ? 0 : 6));
            }
            String o = opciones[i];
            boolean si = o.equals(elegido);
            Button b = boton(o, si ? NARANJA : TARJETA, si ? OSCURO : TEXTO);
            b.setTextSize(14);
            if (!si) b.setBackground(fondo(TARJETA, BORDE, 10));
            else b.setBackground(fondo(NARANJA, 0, 10));
            b.setOnClickListener(v -> alElegir.accept(o));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1);
            p.rightMargin = dp(6);
            fila.addView(b, p);
        }
        // Rellenar la última fila para que los botones tengan el mismo ancho
        int resto = opciones.length % 5;
        if (resto != 0 && fila != null)
            for (int i = resto; i < 5; i++) { View hueco = new View(this); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1); p.rightMargin = dp(6); fila.addView(hueco, p); }
        return todo;
    }

    // ------------------------------------------------------------------ navegación

    void mostrar(String p) {
        pantalla = p;
        contenido.removeAllViews();
        switch (p) {
            case "CONTACTO": pantallaContacto(); break;
            case "LOG": pantallaLog(); break;
            case "ACTIVIDADES": pantallaActividades(); break;
            default: pantallaInicio();
        }
        pintarBarra();
    }

    private void pintarBarra() {
        barra.removeAllViews();
        String[][] items = {{"INICIO", "Inicio"}, {"CONTACTO", "Contacto"}, {"LOG", "LOG"}, {"ACTIVIDADES", "Actividades"}};
        for (String[] it : items) {
            TextView t = texto(it[1], 13, it[0].equals(pantalla) ? NARANJA : GRIS, it[0].equals(pantalla));
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(dp(48));
            t.setOnClickListener(v -> mostrar(it[0]));
            barra.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        }
    }

    // ------------------------------------------------------------------ INICIO

    private void pantallaInicio() {
        LinearLayout cab = new LinearLayout(this);
        cab.setOrientation(LinearLayout.HORIZONTAL);
        cab.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout nom = new LinearLayout(this);
        nom.setOrientation(LinearLayout.VERTICAL);
        nom.addView(texto("Bitácora 26", 13, GRIS, false));
        nom.addView(texto(operador().isEmpty() ? "SIN INDICATIVO" : operador(), 26, TEXTO, true));
        cab.addView(nom, new LinearLayout.LayoutParams(0, -2, 1));
        TextView ajustes = texto("Ajustes", 14, NARANJA, true);
        ajustes.setPadding(dp(12), dp(12), dp(4), dp(12));
        ajustes.setOnClickListener(v -> ajustes());
        cab.addView(ajustes);
        contenido.addView(cab);

        Button apuntar = boton("+  Apuntar contacto", NARANJA, OSCURO);
        apuntar.setTextSize(18);
        apuntar.setOnClickListener(v -> mostrar("CONTACTO"));
        contenido.addView(apuntar, separado(16));

        String hoy = ahoraUtc("dd/MM/yyyy");
        LinearLayout cifras = new LinearLayout(this);
        cifras.setOrientation(LinearLayout.HORIZONTAL);
        String[][] cs = {{String.valueOf(db.contar("1=1")), "Contactos"}, {String.valueOf(db.contar("fecha = ?", hoy)), "Hoy (UTC)"},
                {String.valueOf(db.contar("banda = 'CB'")), "En CB"}};
        for (int i = 0; i < cs.length; i++) {
            LinearLayout t = tarjeta();
            t.addView(texto(cs[i][0], 24, TEXTO, true));
            t.addView(texto(cs[i][1], 12, GRIS, false));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
            if (i < cs.length - 1) p.rightMargin = dp(10);
            cifras.addView(t, p);
        }
        contenido.addView(cifras, separado(14));

        int act = prefs.getInt("actividad", 0);
        String nomAct = act > 0 ? db.nombreActividad(act) : null;
        if (nomAct != null) {
            LinearLayout t = tarjeta();
            t.addView(texto("ACTIVIDAD EN CURSO", 12, NARANJA, true));
            t.addView(texto(nomAct, 18, TEXTO, true));
            t.addView(texto(db.contar("actividad_numeral = ?", String.valueOf(act)) + " contactos", 13, GRIS, false));
            t.setOnClickListener(v -> mostrar("ACTIVIDADES"));
            contenido.addView(t, separado(14));
        }

        if (nube.conectada()) {
            int pend = db.numPendientes();
            TextView sy = texto((estadoSync.isEmpty() ? "Conectado con tu cuenta" : estadoSync)
                    + (pend > 0 ? " · " + pend + " sin enviar" : "") + "  ·  Toca para sincronizar", 12, pend > 0 ? NARANJA : VERDE, false);
            sy.setPadding(0, dp(12), 0, 0);
            sy.setOnClickListener(v -> sincronizar(true));
            contenido.addView(sy);
        } else {
            TextView sy = texto("Sin conectar con el PC · Ajustes → Conectar mi cuenta de Bitácora 26", 12, GRIS, false);
            sy.setPadding(0, dp(12), 0, 0);
            sy.setOnClickListener(v -> conectarCuenta());
            contenido.addView(sy);
        }

        contenido.addView(rotulo("ÚLTIMOS CONTACTOS"));
        List<Db.Qso> ult = db.qsos("TODOS", "", 0, 5, hoy);
        if (ult.isEmpty()) contenido.addView(texto("Todavía no hay contactos. Pulsa «Apuntar contacto».", 14, GRIS, false));
        else contenido.addView(listaQsos(ult, false));
    }

    // ------------------------------------------------------------------ CONTACTO

    private void pantallaContacto() {
        LinearLayout cab = new LinearLayout(this);
        cab.setOrientation(LinearLayout.HORIZONTAL);
        cab.setGravity(Gravity.CENTER_VERTICAL);
        cab.addView(texto("Nuevo contacto", 22, TEXTO, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView reloj = texto("", 13, GRIS, false);
        cab.addView(reloj);
        contenido.addView(cab);
        final String[] fechaHora = {ahoraUtc("dd/MM/yyyy"), ahoraUtc("HH:mm")};
        reloj.setText(fechaHora[0].substring(0, 5) + " · " + fechaHora[1] + " UTC");

        contenido.addView(rotulo("INDICATIVO"));
        EditText ind = campo("EA1XYZ", false, 26);
        ind.setTypeface(Typeface.DEFAULT_BOLD);
        ind.setBackground(fondo(CAMPO, NARANJA, 10));
        contenido.addView(ind);

        TextView aviso = texto("", 13, GRIS, false);
        aviso.setPadding(0, dp(6), 0, 0);
        contenido.addView(aviso);

        EditText nombre = campo("Nombre", false, 16), qth = campo("QTH (ciudad)", false, 16), grid = campo("Locator", false, 16);
        Button verQrz = boton("Ver en QRZ.com", TARJETA, NARANJA);
        verQrz.setTextSize(14);
        verQrz.setBackground(fondo(TARJETA, NARANJA, 10));
        verQrz.setVisibility(View.GONE);
        final String[] qrzDe = {""};
        verQrz.setOnClickListener(v -> abrirQrz(qrzDe[0]));
        final Runnable[] buscarQrz = new Runnable[1];
        buscarQrz[0] = () -> {
            String i = ind.getText().toString().trim().toUpperCase(Locale.ROOT);
            if (i.length() < 3 || "CB".equals(bandaSel) || !qrz.configurado()) { verQrz.setVisibility(View.GONE); return; }
            boolean faltan = nombre.getText().length() == 0 || qth.getText().length() == 0 || grid.getText().length() == 0;
            fondo.execute(() -> {
                String[] d = qrz.buscar(i);
                ui.post(() -> {
                    if (!i.equals(ind.getText().toString().trim().toUpperCase(Locale.ROOT))) return;   // ya ha escrito otro
                    if (d == null || "CB".equals(bandaSel)) { verQrz.setVisibility(View.GONE); return; }
                    qrzDe[0] = i;
                    verQrz.setText("Ver " + i + " en QRZ.com");
                    verQrz.setVisibility(View.VISIBLE);
                    // Solo se rellena lo que falta (primero van los datos propios)
                    if (faltan) {
                        if (nombre.getText().length() == 0 && !d[0].isEmpty()) nombre.setText(d[0].toUpperCase(Locale.ROOT));
                        if (qth.getText().length() == 0 && !d[1].isEmpty()) qth.setText(d[1].toUpperCase(Locale.ROOT));
                        if (grid.getText().length() == 0 && !d[2].isEmpty()) grid.setText(d[2].toUpperCase(Locale.ROOT));
                    }
                });
            });
        };
        final Runnable esperaQrz = () -> buscarQrz[0].run();

        ind.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) {
                String i = s.toString().trim();
                ui.removeCallbacks(esperaQrz);
                verQrz.setVisibility(View.GONE);
                if (i.length() < 3) { aviso.setText(""); return; }
                ui.postDelayed(esperaQrz, 900);   // se busca en QRZ.com cuando deja de escribir
                String ya = db.yaTrabajado(i);
                aviso.setText(ya.isEmpty() ? "Nuevo: no lo tienes en el LOG" : "Ya trabajado: " + ya);
                aviso.setTextColor(ya.isEmpty() ? VERDE : NARANJA);
                String[] d = db.datosDe(i);   // primero los datos propios, como en el PC
                if (d != null) {
                    if (nombre.getText().length() == 0 && d[0] != null) nombre.setText(d[0]);
                    if (qth.getText().length() == 0 && d[1] != null) qth.setText(d[1]);
                    if (grid.getText().length() == 0 && d[2] != null) grid.setText(d[2]);
                }
            }
        });

        contenido.addView(rotulo("BANDA"));
        LinearLayout cajaBanda = new LinearLayout(this);
        cajaBanda.setOrientation(LinearLayout.VERTICAL);
        contenido.addView(cajaBanda);
        contenido.addView(rotulo("MODO"));
        LinearLayout cajaModo = new LinearLayout(this);
        cajaModo.setOrientation(LinearLayout.VERTICAL);
        contenido.addView(cajaModo);
        Runnable[] repintar = new Runnable[1];
        repintar[0] = () -> {
            cajaBanda.removeAllViews();
            cajaBanda.addView(fichas(BANDAS, bandaSel, x -> { bandaSel = x; repintar[0].run(); buscarQrz[0].run(); }));
            cajaModo.removeAllViews();
            cajaModo.addView(fichas(MODOS, modoSel, x -> { modoSel = x; repintar[0].run(); }));
        };
        repintar[0].run();

        LinearLayout rst = new LinearLayout(this);
        rst.setOrientation(LinearLayout.HORIZONTAL);
        EditText rs = campo("RST enviado", true, 20), rr = campo("RST recibido", true, 20);
        rs.setText("59");
        rr.setText("59");
        LinearLayout c1 = new LinearLayout(this); c1.setOrientation(LinearLayout.VERTICAL);
        c1.addView(rotulo("RST ENVIADO")); c1.addView(rs);
        LinearLayout c2 = new LinearLayout(this); c2.setOrientation(LinearLayout.VERTICAL);
        c2.addView(rotulo("RST RECIBIDO")); c2.addView(rr);
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, -2, 1); p1.rightMargin = dp(10);
        rst.addView(c1, p1);
        rst.addView(c2, new LinearLayout.LayoutParams(0, -2, 1));
        contenido.addView(rst);

        contenido.addView(verQrz, separado(10));
        contenido.addView(rotulo("NOMBRE · QTH · LOCATOR"));
        contenido.addView(nombre);
        contenido.addView(qth, separado(8));
        contenido.addView(grid, separado(8));
        EditText coment = campo("Comentario", false, 16);
        contenido.addView(coment, separado(8));

        int act = prefs.getInt("actividad", 0);
        String nomAct = act > 0 ? db.nombreActividad(act) : null;
        contenido.addView(rotulo("ACTIVIDAD"));
        TextView tAct = texto(nomAct == null ? "LOG principal (cámbiala en Actividades)" : nomAct, 16, nomAct == null ? GRIS : NARANJA, nomAct != null);
        contenido.addView(tAct);

        Button guardar = boton("Guardar contacto", VERDE, Color.rgb(8, 36, 15));
        guardar.setTextSize(18);
        guardar.setOnClickListener(v -> {
            String i = ind.getText().toString().trim().toUpperCase(Locale.ROOT);
            if (!i.matches("[A-Z0-9/]{3,15}")) { ind.setError("Indicativo no válido"); return; }
            if (operador().isEmpty()) { pedirIndicativo(false); return; }
            Db.Qso q = new Db.Qso();
            q.indicativo = i;
            // Hora del momento de guardar, siempre en UTC
            q.fecha = ahoraUtc("dd/MM/yyyy");
            q.hora = ahoraUtc("HH:mm");
            q.banda = bandaSel;
            q.modo = modoSel;
            q.rstS = rs.getText().toString().trim();
            q.rstR = rr.getText().toString().trim();
            q.nombre = nombre.getText().toString().trim().toUpperCase(Locale.ROOT);
            q.qth = qth.getText().toString().trim().toUpperCase(Locale.ROOT);
            q.grid = grid.getText().toString().trim().toUpperCase(Locale.ROOT);
            q.comentario = coment.getText().toString().trim().toUpperCase(Locale.ROOT);
            q.operador = operador();
            if (nomAct != null) { q.actividadNumeral = act; q.actividad = nomAct; }
            String uuid = db.guardar(q);
            nube.encolarQso(q, uuid, operador());
            sincronizar(false);
            prefs.edit().putString("banda", bandaSel).putString("modo", modoSel).apply();
            Toast.makeText(this, "Guardado: " + i + " · " + q.hora + " UTC", Toast.LENGTH_SHORT).show();
            mostrar("CONTACTO");   // formulario limpio para el siguiente
        });
        contenido.addView(guardar, separado(18));
        ind.requestFocus();
    }

    // ------------------------------------------------------------------ LOG

    private void pantallaLog() {
        LinearLayout cab = new LinearLayout(this);
        cab.setOrientation(LinearLayout.HORIZONTAL);
        cab.setGravity(Gravity.CENTER_VERTICAL);
        cab.addView(texto("Mi LOG", 22, TEXTO, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView exp = texto("Exportar ADI", 14, NARANJA, true);
        exp.setPadding(dp(10), dp(10), 0, dp(10));
        exp.setOnClickListener(v -> exportarAdi());
        cab.addView(exp);
        contenido.addView(cab);

        EditText busca = campo("Buscar indicativo", false, 16);
        busca.setText(buscaLog);
        contenido.addView(busca, separado(10));

        LinearLayout filtros = new LinearLayout(this);
        filtros.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout lista = new LinearLayout(this);
        lista.setOrientation(LinearLayout.VERTICAL);
        Runnable recargar = () -> {
            lista.removeAllViews();
            List<Db.Qso> qs = db.qsos(filtroLog, buscaLog, 0, 300, ahoraUtc("dd/MM/yyyy"));
            if (qs.isEmpty()) lista.addView(texto("No hay contactos.", 14, GRIS, false));
            else lista.addView(listaQsos(qs, true));
        };
        String[][] fs = {{"TODOS", "Todos"}, {"HF", "HF"}, {"CB", "CB"}, {"HOY", "Hoy"}};
        List<Button> botones = new ArrayList<>();
        for (String[] f : fs) {
            Button b = boton(f[1], TARJETA, TEXTO);
            b.setTextSize(13);
            b.setMinHeight(dp(40));
            botones.add(b);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(42), 1);
            p.rightMargin = dp(6);
            filtros.addView(b, p);
        }
        Runnable pintarFiltros = () -> {
            for (int i = 0; i < fs.length; i++) {
                boolean si = fs[i][0].equals(filtroLog);
                botones.get(i).setBackground(si ? fondo(NARANJA, 0, 20) : fondo(TARJETA, BORDE, 20));
                botones.get(i).setTextColor(si ? OSCURO : TEXTO);
            }
        };
        for (int i = 0; i < fs.length; i++) {
            String f = fs[i][0];
            botones.get(i).setOnClickListener(v -> { filtroLog = f; pintarFiltros.run(); recargar.run(); });
        }
        pintarFiltros.run();
        contenido.addView(filtros, separado(10));
        contenido.addView(lista, separado(12));
        busca.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { buscaLog = s.toString(); recargar.run(); }
        });
        recargar.run();
    }

    /** Lista de contactos en una tarjeta. Con «borrable», una pulsación larga ofrece borrarlo. */
    private View listaQsos(List<Db.Qso> qs, boolean borrable) {
        LinearLayout t = tarjeta();
        t.setPadding(0, 0, 0, 0);
        String fechaAnt = null;
        for (int i = 0; i < qs.size(); i++) {
            Db.Qso q = qs.get(i);
            if (borrable && !q.fecha.equals(fechaAnt)) {
                TextView f = texto(q.fecha, 12, GRIS, true);
                f.setPadding(dp(14), dp(10), dp(14), dp(2));
                t.addView(f);
                fechaAnt = q.fecha;
            }
            LinearLayout fila = new LinearLayout(this);
            fila.setOrientation(LinearLayout.HORIZONTAL);
            fila.setPadding(dp(14), dp(10), dp(14), dp(10));
            LinearLayout izq = new LinearLayout(this);
            izq.setOrientation(LinearLayout.VERTICAL);
            izq.addView(texto(q.indicativo, 16, TEXTO, true));
            StringBuilder det = new StringBuilder();
            if (q.nombre != null && !q.nombre.isEmpty()) det.append(q.nombre);
            if (q.qth != null && !q.qth.isEmpty()) det.append(det.length() > 0 ? " · " : "").append(q.qth);
            det.append(det.length() > 0 ? " · " : "").append(q.rstS).append('/').append(q.rstR);
            izq.addView(texto(det.toString(), 12, GRIS, false));
            fila.addView(izq, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout der = new LinearLayout(this);
            der.setOrientation(LinearLayout.VERTICAL);
            der.setGravity(Gravity.END);
            TextView bm = texto(q.banda + " " + q.modo, 12, colorModo(q.modo), true);
            bm.setGravity(Gravity.END);
            der.addView(bm);
            TextView h = texto(horaParaVer(q.fecha, q.hora) + (prefs.getBoolean("verLocal", false) ? "" : " UTC"), 12, GRIS, false);
            h.setGravity(Gravity.END);
            der.addView(h);
            fila.addView(der);
            if (borrable) {
                fila.setOnLongClickListener(v -> {
                    boolean cb = "CB".equals(q.banda);
                    String[] ops = cb ? new String[]{"Borrar contacto"} : new String[]{"Ver " + q.indicativo + " en QRZ.com", "Borrar contacto"};
                    new AlertDialog.Builder(this).setTitle(q.indicativo).setItems(ops, (dd, w) -> {
                        if (!cb && w == 0) { abrirQrz(q.indicativo); return; }
                        new AlertDialog.Builder(this)
                                .setTitle("Borrar contacto")
                                .setMessage("¿Borrar el contacto con " + q.indicativo + " (" + q.fecha + " " + q.hora + " UTC)?"
                                        + (nube.conectada() ? "\n\nTambién se borrará en tu PC." : ""))
                                .setPositiveButton("Borrar", (d, ww) -> {
                                    db.borrar(q.id);
                                    nube.encolarQso(null, q.uuid, operador());
                                    sincronizar(false);
                                    mostrar("LOG");
                                })
                                .setNegativeButton("Cancelar", null)
                                .show();
                    }).show();
                    return true;
                });
            }
            t.addView(fila);
            if (i < qs.size() - 1) {
                View linea = new View(this);
                linea.setBackgroundColor(Color.rgb(42, 58, 82));
                t.addView(linea, new LinearLayout.LayoutParams(-1, dp(1)));
            }
        }
        return t;
    }

    /** Mismos colores de fonía que la cuadrícula del PC: SSB verde, AM naranja, FM azul. */
    static int colorModo(String m) {
        if (m == null) return GRIS;
        switch (m) {
            case "AM": return NARANJA;
            case "FM": return Color.rgb(108, 180, 255);
            case "CW": return TEXTO;
            case "FT8": case "FT4": return Color.rgb(199, 155, 255);
            default: return VERDE;
        }
    }

    // ------------------------------------------------------------------ ACTIVIDADES

    private void pantallaActividades() {
        contenido.addView(texto("Actividades", 22, TEXTO, true));
        int activa = prefs.getInt("actividad", 0);

        LinearLayout logp = tarjeta();
        logp.addView(texto("LOG principal", 16, activa == 0 ? NARANJA : TEXTO, true));
        logp.addView(texto(activa == 0 ? "En uso: los contactos van solo al LOG" : "Pulsa para apuntar sin actividad", 12, GRIS, false));
        logp.setOnClickListener(v -> { prefs.edit().putInt("actividad", 0).apply(); mostrar("ACTIVIDADES"); });
        if (activa == 0) logp.setBackground(fondo(TARJETA, NARANJA, 14));
        contenido.addView(logp, separado(14));

        for (Db.Actividad a : db.actividades()) {
            LinearLayout t = tarjeta();
            boolean si = a.numeral == activa;
            t.addView(texto(a.numeral + " - " + a.nombre, 16, si ? NARANJA : TEXTO, true));
            t.addView(texto(a.contactos + " contactos" + (si ? " · EN USO" : ""), 12, GRIS, false));
            if (si) t.setBackground(fondo(TARJETA, NARANJA, 14));
            t.setOnClickListener(v -> { prefs.edit().putInt("actividad", a.numeral).apply(); Toast.makeText(this, "Actividad en uso: " + a.nombre, Toast.LENGTH_SHORT).show(); mostrar("ACTIVIDADES"); });
            t.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("Borrar actividad")
                        .setMessage("¿Borrar la actividad «" + a.nombre + "»?\n\nLos contactos NO se borran: se quedan en tu LOG.")
                        .setPositiveButton("Borrar", (d, w) -> {
                            db.borrarActividad(a.numeral);
                            if (a.uuid != null && !a.uuid.isEmpty()) { nube.encolarActividad(a.uuid, null, operador()); sincronizar(false); }
                            if (prefs.getInt("actividad", 0) == a.numeral) prefs.edit().putInt("actividad", 0).apply();
                            mostrar("ACTIVIDADES");
                        })
                        .setNegativeButton("Cancelar", null)
                        .show();
                return true;
            });
            contenido.addView(t, separado(10));
        }

        Button nueva = boton("+  Nueva actividad", TARJETA, NARANJA);
        nueva.setBackground(fondo(TARJETA, NARANJA, 14));
        nueva.setOnClickListener(v -> {
            EditText nombre = campo("Nombre de la actividad", false, 16);
            new AlertDialog.Builder(this)
                    .setTitle("Nueva actividad")
                    .setView(nombre)
                    .setPositiveButton("Crear", (d, w) -> {
                        String n = nombre.getText().toString().trim().toUpperCase(Locale.ROOT);
                        if (n.isEmpty()) return;
                        Object[] na = db.crearActividad(n);
                        int num = (Integer) na[0];
                        nube.encolarActividad((String) na[1], n, operador());
                        sincronizar(false);
                        prefs.edit().putInt("actividad", num).apply();
                        mostrar("ACTIVIDADES");
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
        });
        contenido.addView(nueva, separado(16));
        TextView ayuda = texto("Pulsa una actividad para apuntar en ella. Mantén pulsado para borrarla (sus contactos se quedan en el LOG). "
                + "Las actividades del radioclub se gestionan en el PC.", 12, GRIS, false);
        ayuda.setPadding(0, dp(10), 0, 0);
        contenido.addView(ayuda);
    }

    // ------------------------------------------------------------------ ajustes y exportar

    private void pedirIndicativo(boolean primeraVez) {
        EditText e = campo("Tu indicativo (EA4XXX)", false, 20);
        e.setText(operador());
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(primeraVez ? "Bienvenido a Bitácora 26" : "Tu indicativo")
                .setMessage("Escribe tu indicativo. Irá como operador en cada contacto.")
                .setView(e)
                .setPositiveButton("Guardar", (d, w) -> {
                    String i = e.getText().toString().trim().toUpperCase(Locale.ROOT);
                    if (i.matches("[A-Z0-9/]{3,15}")) prefs.edit().putString("operador", i).apply();
                    mostrar(pantalla);
                });
        if (primeraVez) b.setCancelable(false); else b.setNegativeButton("Cancelar", null);
        b.show();
    }

    private void ajustes() {
        String[] ops = {"Cambiar mi indicativo (" + operador() + ")",
                prefs.getBoolean("verLocal", false) ? "Ver horas en UTC (ahora: hora local)" : "Ver horas en hora local (ahora: UTC)",
                "Exportar todo el LOG (ADI)",
                nube.conectada() ? "Cuenta de Bitácora 26: " + nube.cuenta() + " (desconectar)" : "Conectar mi cuenta de Bitácora 26 (sincronizar con el PC)",
                "Sincronizar ahora con el PC",
                qrz.configurado() ? "Cuenta de QRZ.com: " + prefs.getString("qrzUsuario", "") : "Cuenta de QRZ.com (para buscar nombre y QTH)",
                "Acerca de Bitácora 26 móvil"};
        new AlertDialog.Builder(this).setTitle("Ajustes").setItems(ops, (d, w) -> {
            if (w == 0) pedirIndicativo(false);
            else if (w == 1) { prefs.edit().putBoolean("verLocal", !prefs.getBoolean("verLocal", false)).apply(); mostrar(pantalla); }
            else if (w == 2) exportarAdi();
            else if (w == 3) { if (nube.conectada()) desconectarCuenta(); else conectarCuenta(); }
            else if (w == 4) sincronizar(true);
            else if (w == 5) cuentaQrz();
            else new AlertDialog.Builder(this).setTitle("Bitácora 26 móvil " + VERSION)
                        .setMessage("LOG de bolsillo, acompañante de Bitácora 26 para PC.\n\nLa hora se guarda siempre en UTC. "
                                + "Exporta el LOG en ADI y lo importas en el PC (Archivo → Importar).\n\nbitacora26.es · EA4IXR")
                        .setPositiveButton("Vale", null)
                        .setNeutralButton("Abrir la web", (x, y) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://bitacora26.es"))))
                        .show();
        }).show();
    }

    private void abrirQrz(String call) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.qrz.com/db/" + Uri.encode(call)))); } catch (Exception ignored) { }
    }

    private EditText campoClave(String pista) {
        EditText e = new EditText(this);
        e.setHint(pista);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    private LinearLayout caja(View... vs) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(8), dp(20), 0);
        for (View v : vs) l.addView(v, separado(8));
        return l;
    }

    /** Usuario y contraseña de QRZ.com (los escribe el usuario; se guardan solo en este móvil). */
    private void cuentaQrz() {
        EditText u = new EditText(this);
        u.setHint("Usuario de QRZ.com");
        u.setSingleLine(true);
        u.setText(prefs.getString("qrzUsuario", ""));
        EditText c = campoClave("Contraseña de QRZ.com");
        TextView nota = texto("Hace falta una suscripción de QRZ.com que permita consultas XML (como en el PC). En banda CB nunca se consulta.", 12, Color.GRAY, false);
        new AlertDialog.Builder(this).setTitle("Cuenta de QRZ.com").setView(caja(u, c, nota))
                .setPositiveButton("Guardar", (d, w) -> {
                    String usuario = u.getText().toString().trim(), clave = c.getText().toString();
                    if (usuario.isEmpty()) { prefs.edit().remove("qrzUsuario").remove("qrzClave").apply(); return; }
                    SharedPreferences.Editor ed = prefs.edit().putString("qrzUsuario", usuario);
                    if (!clave.isEmpty()) ed.putString("qrzClave", clave);
                    ed.apply();
                    fondo.execute(() -> {
                        String e = qrz.probar();
                        ui.post(() -> Toast.makeText(this, e == null ? "QRZ.com: cuenta correcta" : "QRZ.com: " + e, Toast.LENGTH_LONG).show());
                    });
                })
                .setNegativeButton("Cancelar", null).show();
    }

    /** Conectar el móvil a la cuenta de Bitácora 26: indicativo + contraseña, y luego el código que llega al correo. */
    private void conectarCuenta() {
        EditText ind = campo("Indicativo (o correo) de tu cuenta", false, 16);
        ind.setFilters(new InputFilter[0]);
        ind.setText(operador());
        EditText clave = campoClave("Contraseña de Bitácora 26");
        TextView nota = texto("Es la cuenta que usas en el programa del PC. Tu PC tiene que tener activado «Sincronizar mis ordenadores».", 12, Color.GRAY, false);
        new AlertDialog.Builder(this).setTitle("Conectar con el PC").setView(caja(ind, clave, nota))
                .setPositiveButton("Siguiente", (d, w) -> {
                    String quien = ind.getText().toString().trim(), c = clave.getText().toString();
                    if (quien.isEmpty() || c.isEmpty()) return;
                    fondo.execute(() -> {
                        try {
                            String correo = nube.vincular(quien, c);
                            ui.post(() -> pedirCodigo(quien, correo));
                        } catch (Exception e) {
                            ui.post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
                        }
                    });
                })
                .setNegativeButton("Cancelar", null).show();
    }

    private void pedirCodigo(String quien, String correo) {
        EditText cod = campo("Código", true, 20);
        new AlertDialog.Builder(this).setTitle("Código del correo")
                .setMessage("Te hemos mandado un código a " + correo + ". Escríbelo aquí.")
                .setView(caja(cod)).setCancelable(false)
                .setPositiveButton("Conectar", (d, w) -> {
                    String c = cod.getText().toString().trim();
                    fondo.execute(() -> {
                        try {
                            String ind = nube.vincularCodigo(quien, c);
                            ui.post(() -> {
                                if (!ind.isEmpty() && !ind.equals(operador())) prefs.edit().putString("operador", ind).apply();
                                subirLoQueYaHabia();
                                new AlertDialog.Builder(this).setTitle("Móvil conectado")
                                        .setMessage("Listo. Abre Bitácora 26 en tu PC (o pulsa Sincronizar allí) para que te mande tu LOG. "
                                                + "A partir de ahora, lo que apuntes aquí llegará al PC y al revés.")
                                        .setPositiveButton("Vale", null).show();
                                sincronizar(false);
                            });
                        } catch (Exception e) {
                            ui.post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
                        }
                    });
                })
                .setNegativeButton("Cancelar", null).show();
    }

    /** Lo apuntado en el móvil antes de conectar también va al PC. */
    private void subirLoQueYaHabia() {
        for (Db.Actividad a : db.actividades()) if (a.uuid != null && !a.uuid.isEmpty()) nube.encolarActividad(a.uuid, a.nombre, operador());
        for (Db.Qso q : db.todos()) nube.encolarQso(q, q.uuid, operador());
    }

    private void desconectarCuenta() {
        new AlertDialog.Builder(this).setTitle("Desconectar")
                .setMessage("El móvil dejará de sincronizar con tu PC. Tus contactos se quedan en el móvil.")
                .setPositiveButton("Desconectar", (d, w) -> { nube.desconectar(); mostrar(pantalla); })
                .setNegativeButton("Cancelar", null).show();
    }

    private void exportarAdi() {
        List<Db.Qso> qs = db.todos();
        if (qs.isEmpty()) { Toast.makeText(this, "No hay contactos que exportar", Toast.LENGTH_SHORT).show(); return; }
        try {
            File dir = new File(getCacheDir(), "exportar");
            dir.mkdirs();
            File f = new File(dir, "bitacora26_movil_" + ahoraUtc("yyyyMMdd_HHmm") + ".adi");
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(Adi.exportar(qs, VERSION).getBytes(StandardCharsets.UTF_8)); }
            Uri uri = FileProvider.getUriForFile(this, "es.bitacora26.movil.archivos", f);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/octet-stream");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.putExtra(Intent.EXTRA_SUBJECT, "LOG Bitácora 26 móvil (" + qs.size() + " contactos)");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Enviar el LOG (ADI)"));
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo exportar: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
