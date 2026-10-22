package cn.datadashboard.security;

import java.security.Principal;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public final class AccountManagementController {
    public record Create(String username,String password,String role) { @Override public String toString(){return "Create[REDACTED]";} }
    public record Delete(boolean confirmed) { }
    private final PrivateAccountStore accounts;
    public AccountManagementController(PrivateAccountStore accounts){this.accounts=accounts;}
    @GetMapping("/api/admin/accounts") public PrivateAccountStore.Listing list(){return accounts.list();}
    @PostMapping("/api/admin/accounts") public PrivateAccountStore.Listing create(@RequestBody Create value){return accounts.create(value.username(),value.password(),value.role());}
    @DeleteMapping("/api/admin/accounts/{username}") public PrivateAccountStore.Listing delete(Principal actor,@PathVariable String username,@RequestBody Delete value){return accounts.delete(actor.getName(),username,value.confirmed());}
    @ExceptionHandler(PrivateAccountStore.Failure.class) public ResponseEntity<Map<String,String>> failure(PrivateAccountStore.Failure e){
        return ResponseEntity.status("ACCOUNT_STORE_UNAVAILABLE".equals(e.getMessage())?503:400).cacheControl(CacheControl.noStore()).body(Map.of("code",e.getMessage()));
    }
}
