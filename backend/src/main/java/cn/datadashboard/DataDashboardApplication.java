package cn.datadashboard;

import cn.datadashboard.local.LocalDashboardMain;

public class DataDashboardApplication {

    public static void main(String[] args) {
        // Until deployment configuration is separately approved, the default entry is local-only.
        LocalDashboardMain.main(args);
    }
}
