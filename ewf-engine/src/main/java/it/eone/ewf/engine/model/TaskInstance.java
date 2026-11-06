package it.eone.ewf.engine.model;

import java.time.Instant;

/** Task eseguito/in corso (ex TASKRUN). Mutabile solo dentro il motore. */
public final class TaskInstance {
    public long id;
    public long processId;
    public long taskDefId;
    public int stepNo;
    public int subopNo;
    public TaskStatus status = TaskStatus.OPEN;
    public Long assigneeId;
    public String teamCode;
    public Long claimedById;
    public Instant claimedAt;
    public String roleCode;
    public Long fromUserId;
    public Long movedToId;
    public Long actedById;
    public boolean actedAsSubstitute;
    public String action;
    public String note;
    public Instant createdAt;
    public Instant closedAt;
    public int rowVersion;

    public boolean isOpen() { return status == TaskStatus.OPEN || status == TaskStatus.EXECUTING; }

    public TaskInstance copy() {
        TaskInstance c = new TaskInstance();
        c.id = id; c.processId = processId; c.taskDefId = taskDefId; c.stepNo = stepNo; c.subopNo = subopNo;
        c.status = status; c.assigneeId = assigneeId; c.teamCode = teamCode; c.claimedById = claimedById;
        c.claimedAt = claimedAt; c.roleCode = roleCode; c.fromUserId = fromUserId; c.movedToId = movedToId;
        c.actedById = actedById; c.actedAsSubstitute = actedAsSubstitute; c.action = action; c.note = note;
        c.createdAt = createdAt; c.closedAt = closedAt; c.rowVersion = rowVersion;
        return c;
    }
}
