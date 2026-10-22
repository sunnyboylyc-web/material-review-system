package cn.datadashboard.security;

import java.nio.file.*;
import java.util.*;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Private identity registry. Tombstones prevent a later user inheriting old private state. */
public final class PrivateAccountStore implements UserDetailsService {
    record Account(String username, String passwordHash, String role, boolean deleted) { @Override public String toString(){return "Account[REDACTED]";} }
    record Document(int version, long revision, List<Account> accounts) { }
    public record Summary(String username, String role) { }
    public record Listing(long revision, List<Summary> accounts) { }
    public static final class Failure extends RuntimeException { public Failure(String code) { super(code); } }
    private final Path file;
    private final PasswordEncoder encoder;
    private final tools.jackson.databind.json.JsonMapper json = new tools.jackson.databind.json.JsonMapper();
    private Document document;
    public PrivateAccountStore(Path directory, PasswordEncoder encoder, String viewer, String viewerHash,
                               String operations, String operationsHash, String adminHash) {
        this(directory, encoder, viewer, viewerHash, operations, operationsHash, adminHash, false);
    }
    public PrivateAccountStore(Path directory, PasswordEncoder encoder, String viewer, String viewerHash,
                               String operations, String operationsHash, String adminHash, boolean adminOnly) {
        this.file=directory.resolve("accounts.json"); this.encoder=encoder;
        try {
            if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)) {
                if(Files.isSymbolicLink(file)||Files.size(file)>1024*1024) throw new IllegalStateException();
                document=json.readValue(Files.readString(file),Document.class);
                validate(document);
                if(adminHash!=null&&!adminHash.isBlank()&&document.accounts().stream().noneMatch(a->"ADMIN".equals(a.role())&&!a.deleted()))
                    throw new IllegalStateException("ADMIN_BOOTSTRAP_REQUIRED");
            } else {
                var accounts=new ArrayList<Account>();
                if (adminOnly && (adminHash == null || adminHash.isBlank())) throw new IllegalStateException();
                if (!adminOnly) {
                    accounts.add(new Account(viewer,viewerHash,"VIEWER",false));
                    accounts.add(new Account(operations,operationsHash,"OPERATIONS",false));
                }
                if(adminHash!=null&&!adminHash.isBlank()) accounts.add(new Account("admin",adminHash,"ADMIN",false));
                document=new Document(1,0,List.copyOf(accounts)); validate(document); persist(document);
            }
        } catch(Exception failure) { throw new IllegalStateException("ACCOUNT_STORE_UNAVAILABLE"); }
    }
    private static boolean nameValid(String name) { return name!=null&&name.matches("[A-Za-z0-9_-]{1,40}"); }
    private static void validate(Document doc) {
        if(doc==null||doc.version()!=1||doc.revision()<0||doc.accounts()==null||doc.accounts().size()>1000) throw new IllegalArgumentException();
        var names=new HashSet<String>();
        for(var a:doc.accounts()) {
            if(a==null||!nameValid(a.username())||!names.add(a.username())||!Set.of("VIEWER","OPERATIONS","ADMIN").contains(a.role())
                    ||(!a.deleted()&&(a.passwordHash()==null||a.passwordHash().isBlank()))
                    ||("ADMIN".equals(a.role())&&(!"admin".equals(a.username())||a.deleted()))) throw new IllegalArgumentException();
        }
    }
    private void persist(Document next) {
        try { String encoded=json.writeValueAsString(next); if(encoded.length()>1024*1024) throw new IllegalArgumentException(); PrivateUserFiles.write(file,encoded); }
        catch(Exception failure) { throw new Failure("ACCOUNT_STORE_UNAVAILABLE"); }
    }
    public synchronized Listing list() { return new Listing(document.revision(),document.accounts().stream().filter(a->!a.deleted()).map(a->new Summary(a.username(),a.role())).toList()); }
    @Override public synchronized UserDetails loadUserByUsername(String username) {
        Account a=document.accounts().stream().filter(v->!v.deleted()&&v.username().equals(username)).findFirst().orElseThrow(()->new UsernameNotFoundException("ACCOUNT_UNAVAILABLE"));
        String[] roles=switch(a.role()) { case "ADMIN"->new String[]{"ADMIN","OPERATIONS","VIEWER"}; case "OPERATIONS"->new String[]{"OPERATIONS","VIEWER"}; default->new String[]{"VIEWER"}; };
        return User.withUsername(a.username()).password(a.passwordHash()).roles(roles).build();
    }
    public synchronized Listing create(String username,String password,String role) {
        if(!nameValid(username)||"admin".equals(username)||password==null||password.codePointCount(0,password.length())<6||password.codePointCount(0,password.length())>30
                ||!Set.of("VIEWER","OPERATIONS").contains(role==null?"":role)) throw new Failure("ACCOUNT_INVALID");
        if(document.accounts().stream().anyMatch(a->a.username().equals(username))) throw new Failure("ACCOUNT_NAME_UNAVAILABLE");
        if(document.accounts().size()>=1000) throw new Failure("ACCOUNT_LIMIT_REACHED");
        var next=new ArrayList<>(document.accounts()); next.add(new Account(username,encoder.encode(password),role,false));
        Document update=new Document(1,document.revision()+1,List.copyOf(next)); persist(update); document=update; return list();
    }
    public synchronized Listing delete(String actor,String username,boolean confirmed) {
        if(!confirmed) throw new Failure("ACCOUNT_CONFIRM_REQUIRED");
        if(username==null||username.equals(actor)||"admin".equals(username)) throw new Failure("ACCOUNT_DELETE_FORBIDDEN");
        if(document.accounts().stream().noneMatch(a->!a.deleted()&&a.username().equals(username))) throw new Failure("ACCOUNT_NOT_FOUND");
        var next=document.accounts().stream().map(a->a.username().equals(username)?new Account(a.username(),"",a.role(),true):a).toList();
        Document update=new Document(1,document.revision()+1,next); persist(update); document=update; return list();
    }
}
