package cn.datadashboard.security;

public final class OperationActor {
    private OperationActor() { }
    public static boolean valid(String value) {
        return "LOCAL_UNAUTHENTICATED".equals(value)
                || (value != null && value.matches("AUTHENTICATED:[A-Za-z0-9_-]{1,40}"));
    }
    public static String authenticated(String username) {
        String actor = "AUTHENTICATED:" + username;
        if (!valid(actor)) throw new IllegalArgumentException("ACTOR_INVALID");
        return actor;
    }
}
