package top.zainzh.rearscreen;

/** Exact component identity for the controlled host, independent of the app id's suffix. */
public final class ControlledCastHostComponent {
    private ControlledCastHostComponent() { }

    public static String expected(String applicationPackage, String actualClassName) {
        if (applicationPackage == null || applicationPackage.isEmpty() || applicationPackage.indexOf('/') >= 0
                || actualClassName == null || actualClassName.indexOf('/') >= 0
                || actualClassName.indexOf('.') <= 0 || actualClassName.endsWith(".")) return null;
        return applicationPackage + "/" + actualClassName;
    }

    public static boolean matches(String actual, String applicationPackage, String actualClassName) {
        String expected = expected(applicationPackage, actualClassName);
        String canonicalActual = RearDisplayCastOccupancyReader.canonicalComponent(actual);
        String canonicalExpected = RearDisplayCastOccupancyReader.canonicalComponent(expected);
        return expected != null && canonicalActual != null && canonicalActual.equals(canonicalExpected);
    }
}
