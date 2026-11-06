package it.eone.ewf.engine.memory;

import it.eone.ewf.engine.model.User;
import it.eone.ewf.engine.spi.UserDirectory;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

public final class InMemoryUserDirectory implements UserDirectory {
    private record Subst(long userId, long substituteId, LocalDate from, LocalDate to) {}

    private final Map<Long, User> users = new HashMap<>();
    private final Map<Long, Integer> weights = new HashMap<>();
    private final Map<String, Integer> processWeights = new HashMap<>();
    private final List<Subst> substitutions = new ArrayList<>();
    private final Map<String, Set<Long>> teams = new HashMap<>();

    public User add(long id, String login, String name, String sapUser, int weight) {
        User u = new User(id, login, name, sapUser, true);
        users.put(id, u);
        weights.put(id, weight);
        return u;
    }

    public void deactivate(long id) {
        User u = users.get(id);
        users.put(id, new User(u.id(), u.login(), u.displayName(), u.sapUser(), false));
    }

    public void processWeight(long userId, String processType, int weight) {
        processWeights.put(userId + "|" + processType, weight);
    }

    public void substitution(long userId, long substituteId, LocalDate from, LocalDate to) {
        substitutions.add(new Subst(userId, substituteId, from, to));
    }

    public void teamMember(String team, long userId) {
        teams.computeIfAbsent(team, k -> new HashSet<>()).add(userId);
    }

    @Override public Optional<User> byId(long id) { return Optional.ofNullable(users.get(id)); }
    @Override public Optional<User> byLogin(String login) {
        return users.values().stream().filter(u -> u.login().equals(login)).findFirst();
    }
    @Override public Optional<User> bySapUser(String sap) {
        return users.values().stream().filter(u -> sap.equals(u.sapUser())).findFirst();
    }
    @Override public int effectiveWeight(long userId, String processType) {
        Integer w = processWeights.get(userId + "|" + processType);
        return w != null ? w : weights.getOrDefault(userId, 0);
    }
    @Override public boolean isSubstitute(long substituteId, long userId, LocalDate date) {
        return substitutions.stream().anyMatch(s -> s.userId() == userId && s.substituteId() == substituteId
                && !date.isBefore(s.from()) && !date.isAfter(s.to()));
    }
    @Override public Set<Long> teamMembers(String team, LocalDate date) {
        return Set.copyOf(teams.getOrDefault(team, Set.of()));
    }
    @Override public Set<String> teamsOf(long userId, LocalDate date) {
        return teams.entrySet().stream().filter(e -> e.getValue().contains(userId))
                .map(Map.Entry::getKey).collect(Collectors.toSet());
    }
    @Override public Set<Long> substitutedBy(long substituteId, LocalDate date) {
        return substitutions.stream().filter(s -> s.substituteId() == substituteId
                        && !date.isBefore(s.from()) && !date.isAfter(s.to()))
                .map(Subst::userId).collect(Collectors.toSet());
    }
}
