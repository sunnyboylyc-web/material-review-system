package cn.datadashboard.local;

/** HTTPS proxy -> loopback authenticated service; deliberately separate from the local runner. */
public final class TrialDashboardMain {
    private TrialDashboardMain() { }
    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("TRIAL_FACT_CONFIGURATION_AND_PROJECT_STORE_REQUIRED");
            System.exit(2);
        }
        LocalDashboardMain.start(new String[]{args[0], "--configuration", args[1],
                "--allow-local-config-writes", "--allow-local-history-writes"}, true);
    }
}
