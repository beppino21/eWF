package it.eone.ewf.sap.migration;

import it.eone.ewf.engine.model.ProcessType;
import it.eone.ewf.engine.model.ProcessVersion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Cosa verra' scritto: oggetti del nuovo modello, con riferimenti agli utenti per login. */
public final class MigrationPlan {

    public record User(String login, String displayName, String sapUser, int weight, String email, boolean active) {}
    public record Rule(String processType, String role, String purchasingGroup, String plant, String storageLocation,
                       BigDecimal amountFrom, BigDecimal amountTo, String currency, String userLogin,
                       LocalDate validFrom, LocalDate validTo, String source) {}
    public record Substitution(String userLogin, String substituteLogin, LocalDate from, LocalDate to) {}
    public record Weight(String userLogin, String processType, int weight) {}
    public record Member(String team, String userLogin) {}

    public final List<ProcessType> types = new ArrayList<>();
    public final List<ProcessVersion> versions = new ArrayList<>();
    public final Map<String, String> roles = new TreeMap<>();
    public final Map<String, String> teams = new TreeMap<>();
    public final List<User> users = new ArrayList<>();
    public final List<Member> members = new ArrayList<>();
    public final List<Rule> rules = new ArrayList<>();
    public final List<Substitution> substitutions = new ArrayList<>();
    public final List<Weight> weights = new ArrayList<>();
    /** codice handler -> contratto (TASK_ACTION, ASSIGNEE, PROCESS_END) */
    public final Map<String, String> handlers = new TreeMap<>();
    /** codice handler -> dove e' usato */
    public final Map<String, SortedSet<String>> handlerUsage = new TreeMap<>();
    public final List<Issue> issues = new ArrayList<>();
    public final Map<String, Integer> readCounts = new LinkedHashMap<>();
    public final List<String> excludedProcesses = new ArrayList<>();
}
