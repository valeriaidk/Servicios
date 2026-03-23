package pe.extech.utilitarios.util;

/**
 * Validaciones locales antes de consumir APIs externas.
 * Evita cobros por requests inválidos al proveedor.
 */
public final class ValidadorUtil {

    private ValidadorUtil() {}

    /**
     * Convierte un valor de columna BIT/BOOLEAN de SQL Server a boolean de Java.
     *
     * El driver Microsoft JDBC mapea columnas BIT a java.lang.Boolean, no a Integer.
     * Pero para columnas derivadas (DECLARE @v BIT; SELECT @v) puede llegar como Boolean o Integer.
     * Este helper cubre ambos casos sin lanzar ClassCastException.
     *
     * Uso: boolean activo = ValidadorUtil.bit(row.get("Activo"));
     */
    public static boolean bit(Object val) {
        if (val == null) return false;
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof Number) return ((Number) val).intValue() == 1;
        return false;
    }

    /** DNI: exactamente 8 dígitos numéricos */
    public static boolean esDniValido(String dni) {
        return dni != null && dni.matches("^[0-9]{8}$");
    }

    /** RUC: 11 dígitos, comienza con 10 o 20 */
    public static boolean esRucValido(String ruc) {
        return ruc != null && ruc.matches("^(10|20)[0-9]{9}$");
    }

    /** Teléfono: formato +51XXXXXXXXX o 51XXXXXXXXX (9-15 dígitos) */
    public static boolean esTelefonoValido(String telefono) {
        return telefono != null && telefono.matches("^\\+?[0-9]{9,15}$");
    }

    /** Correo: formato básico user@domain.tld */
    public static boolean esCorreoValido(String correo) {
        return correo != null && correo.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    }

    public static void validarDni(String dni) {
        if (!esDniValido(dni)) {
            throw new IllegalArgumentException("El DNI debe tener exactamente 8 dígitos numéricos.");
        }
    }

    public static void validarRuc(String ruc) {
        if (!esRucValido(ruc)) {
            throw new IllegalArgumentException("El RUC debe tener 11 dígitos y comenzar con 10 o 20.");
        }
    }

    public static void validarTelefono(String telefono) {
        if (!esTelefonoValido(telefono)) {
            throw new IllegalArgumentException("Formato de teléfono no válido. Use +51XXXXXXXXX.");
        }
    }

    public static void validarCorreo(String correo) {
        if (!esCorreoValido(correo)) {
            throw new IllegalArgumentException("El correo electrónico no tiene un formato válido.");
        }
    }
}
