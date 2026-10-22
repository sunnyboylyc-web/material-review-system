package cn.datadashboard.local;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TrialPortTest {
    @Test void onlyExplicitRecoveryPortIsAllowed() {
        assertEquals("18080",LocalDashboardMain.trialPort(null));
        assertEquals("18081",LocalDashboardMain.trialPort("18081"));
        assertThrows(IllegalArgumentException.class,()->LocalDashboardMain.trialPort("0"));
        assertThrows(IllegalArgumentException.class,()->LocalDashboardMain.trialPort("443"));
    }
}
