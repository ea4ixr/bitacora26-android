package es.bitacora26.movil;

import java.util.List;

/** Exportar ADI con los mismos campos que Bitácora 26 de PC (hora en UTC y operador de cada contacto). */
final class Adi {

    private Adi() { }

    static String exportar(List<Db.Qso> qsos, String version) {
        StringBuilder sb = new StringBuilder();
        sb.append("Exportado desde Bitácora 26 móvil\n");
        campo(sb, "ADIF_VER", "3.1.4");
        campo(sb, "PROGRAMID", "Bitacora26-movil");
        campo(sb, "PROGRAMVERSION", version);
        sb.append("<EOH>\n");
        for (Db.Qso q : qsos) {
            campo(sb, "CALL", q.indicativo);
            if (q.fecha != null && q.fecha.length() == 10)
                campo(sb, "QSO_DATE", q.fecha.substring(6) + q.fecha.substring(3, 5) + q.fecha.substring(0, 2));
            if (q.hora != null && !q.hora.isEmpty()) campo(sb, "TIME_ON", q.hora.replace(":", "") + "00");
            campo(sb, "BAND", q.banda);
            if ("FT4".equalsIgnoreCase(q.modo)) { campo(sb, "MODE", "MFSK"); campo(sb, "SUBMODE", "FT4"); }
            else campo(sb, "MODE", q.modo);
            campo(sb, "RST_SENT", q.rstS);
            campo(sb, "RST_RCVD", q.rstR);
            campo(sb, "NAME", q.nombre);
            campo(sb, "QTH", q.qth);
            campo(sb, "GRIDSQUARE", q.grid);
            campo(sb, "COMMENT", q.comentario);
            if (q.actividad != null && !q.actividad.isEmpty()) campo(sb, "MY_SOTA_REF", q.actividad);
            campo(sb, "OPERATOR", q.operador);
            campo(sb, "STATION_CALLSIGN", q.operador);
            sb.append("<EOR>\n");
        }
        return sb.toString();
    }

    private static void campo(StringBuilder sb, String nombre, String valor) {
        if (valor == null || valor.isEmpty()) return;
        sb.append('<').append(nombre).append(':').append(valor.length()).append('>').append(valor).append(' ');
    }
}
