package dev.weather.oneplus;

public final class AndroidLocationLabelsCheck {
    private static int checks;
    private static void equal(String expected, String actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual);
    }
    private static void same(boolean expected, String a, String b) {
        checks++;
        if (AndroidLocationLabels.samePlace(a, b) != expected)
            throw new AssertionError("Unexpected place comparison: " + a + " / " + b);
    }
    private static String label(String street, String premises, String feature, String number, boolean nearby) {
        return AndroidLocationLabels.choose("青岛市", "山东省", "青岛市", "", "黄岛区",
                street, premises, feature, number, nearby);
    }
    public static void main(String[] args) {
        equal("长江路", label("长江路", "科技中心", "总部", "", false));
        equal("科技中心", label("长江路", "科技中心", "总部", "", true));
        equal("总部", label("长江路", "", "总部", "", true));
        equal("长江路", label("长江路", "青岛", "青岛市", "", true));
        equal("长江路", label("长江路", "山东省", "青岛市", "", true));
        equal("长江路", label("长江路", "", "123", "", true));
        equal("长江路", label("长江路", "", "１２３号", "", true));
        equal("长江路", label("长江路", "", "12A", "12A", true));
        equal("长江路", label("长江路", "", "12A", "", true));
        equal("长江路", label("长江路", "", "No. 123", "", true));
        equal("Building 3", label("长江路", "", "Building 3", "", true));
        equal("3号楼总部", label("长江路", "", "3号楼总部", "", true));
        equal("黄岛区", label("  ", "", "", "", false));
        equal("", AndroidLocationLabels.choose("青岛市", "山东省", "青岛市", "", "青岛",
                "青岛市", "", "", "", true));
        equal("", AndroidLocationLabels.choose("黄岛区", "山东省", "青岛市", "", "黄岛区",
                "", "", "黄岛区", "", false));
        equal("", AndroidLocationLabels.choose("黄岛区", "山东省", "青岛市", "", "黄岛区",
                "", "", "黄岛区", "", true));
        equal("科技中心", AndroidLocationLabels.choose("黄岛区", "山东省", "青岛市", "", "黄岛区",
                "长江路", "科技中心", "黄岛区", "", true));
        equal("", AndroidLocationLabels.choose(null, null, null, null, null, null, null, null, null, true));
        equal("长江路", label("  长江路  ", "", "", "", false));
        equal("", AndroidLocationLabels.distinctStreet("青岛市", "青岛"));
        equal("黄岛区街道", AndroidLocationLabels.distinctStreet("黄岛区", "黄岛区街道"));
        same(true, "青岛", "青岛市");
        same(true, " LONDON ", "london");
        same(false, "黄岛区", "黄岛区街道");
        same(false, "黄岛区", "黄岛镇");
        same(false, "青岛市", "青岛区");
        same(false, "朝阳区", "朝阳");
        same(false, "青岛市市", "青岛");
        same(false, "", "");
        System.out.println("PASS: " + checks + " provider label checks");
    }
}
