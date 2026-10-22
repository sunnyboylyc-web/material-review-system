package cn.datadashboard.security;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;

class PrivateAccountStoreTest {
    @TempDir Path root;
    @Test void adminOnlyBootstrapCanManageUsersAndNeverRecreatesLegacyAccounts() {
        var config = new TrialSecurityConfiguration();
        var encoder = config.trialPasswordEncoder();
        String hash = new BCryptPasswordEncoder().encode("admin-test");
        var accounts = config.trialUsers(encoder,root.toString(),true,hash,"123456","","asdfgh","");
        assertEquals(java.util.List.of(new PrivateAccountStore.Summary("admin","ADMIN")), accounts.list().accounts());
        assertTrue(encoder.matches("admin-test",accounts.loadUserByUsername("admin").getPassword()));
        assertThrows(UsernameNotFoundException.class,()->accounts.loadUserByUsername("123456"));
        accounts.create("new-viewer","viewer-test","VIEWER");
        accounts.create("new-ops","ops-test","OPERATIONS");
        var reopened = config.trialUsers(encoder,root.toString(),true,hash,"123456","","asdfgh","");
        assertEquals(3,reopened.list().accounts().size());
        reopened.delete("admin","new-viewer",true);
        assertThrows(UsernameNotFoundException.class,()->reopened.loadUserByUsername("new-viewer"));
        assertThrows(IllegalStateException.class,()->config.trialUsers(encoder,root.toString(),true,"","123456","","asdfgh",""));
    }
    private PrivateAccountStore store() {
        var bcrypt=new BCryptPasswordEncoder();
        return new PrivateAccountStore(root,new TrialSecurityConfiguration().trialPasswordEncoder(),"123456",bcrypt.encode("viewer-test"),"asdfgh",bcrypt.encode("ops-test"),bcrypt.encode("admin-test"));
    }
    @Test void legacyPreservedUnicodePasswordDurableAndPrivateListing() throws Exception {
        var accounts=store(); var encoder=new TrialSecurityConfiguration().trialPasswordEncoder();
        assertEquals(0,accounts.list().revision());
        assertTrue(encoder.matches("viewer-test",accounts.loadUserByUsername("123456").getPassword()));
        String password="大".repeat(30);
        accounts.create("new-user",password,"OPERATIONS");
        var restored=store();
        assertTrue(encoder.matches(password,restored.loadUserByUsername("new-user").getPassword()));
        assertEquals(1,restored.list().revision());
        assertFalse(Files.readString(root.resolve("accounts.json")).contains(password));
        assertFalse(restored.list().toString().contains("password"));
        assertEquals(3,restored.loadUserByUsername("admin").getAuthorities().size());
    }
    @Test void deletionTombstoneAndGuards() {
        var accounts=store();
        assertThrows(PrivateAccountStore.Failure.class,()->accounts.delete("admin","admin",true));
        assertThrows(PrivateAccountStore.Failure.class,()->accounts.delete("admin","asdfgh",false));
        assertThrows(PrivateAccountStore.Failure.class,()->accounts.create("test","123456","ADMIN"));
        accounts.delete("admin","asdfgh",true);
        assertThrows(UsernameNotFoundException.class,()->accounts.loadUserByUsername("asdfgh"));
        assertThrows(PrivateAccountStore.Failure.class,()->accounts.create("asdfgh","123456","OPERATIONS"));
        assertThrows(UsernameNotFoundException.class,()->store().loadUserByUsername("asdfgh"));
        assertEquals(1,accounts.list().revision());
        for(String invalid:new String[]{"bad name","../file",""}) assertThrows(PrivateAccountStore.Failure.class,()->accounts.create(invalid,"123456","VIEWER"));
    }
    @Test void failedSaveKeepsExistingUsersAndRevision() throws Exception {
        var accounts=store();
        Path file=root.resolve("accounts.json"); byte[] prior=Files.readAllBytes(file);
        Files.delete(file);Files.createDirectory(file);Files.write(file.resolve("prior"),prior);
        assertThrows(PrivateAccountStore.Failure.class,()->accounts.delete("admin","asdfgh",true));
        assertNotNull(accounts.loadUserByUsername("asdfgh"));assertEquals(0,accounts.list().revision());
    }
}
