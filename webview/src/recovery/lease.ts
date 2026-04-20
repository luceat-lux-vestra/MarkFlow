import { app } from "../state";
import { emitToIntelliJLog } from "../bridge";

export type RecoveryBridgeResponse = {
    role?: string;
    epoch?: number;
};

export function clearRecoveryState(reason: string) {
    if (app.activeRecoveryEpoch === null && app.activeRecoveryRole === null && !app.recoveryRequestInFlight) {
        return;
    }
    emitToIntelliJLog(
        `MARKFLOW_UI recovery:clear reason=${reason} epoch=${app.activeRecoveryEpoch ?? -1} role=${app.activeRecoveryRole ?? "none"} sessionId=${(window as any).__markflowSessionId ?? "unknown"}`
    );
    app.activeRecoveryEpoch = null;
    app.activeRecoveryRole = null;
    app.recoveryRequestInFlight = false;
}

export function notifyRecoveryOutcome(status: "complete" | "failed", epoch: number, reason: string) {
    if (!(window as any).cefQuery) {
        clearRecoveryState(`notify:${status}:bridgeMissing`);
        return;
    }

    const currentSessionId = (window as any).__markflowSessionId;
    const request = JSON.stringify({
        action: `recovery:${status}`,
        sessionId: currentSessionId,
        epoch,
        reason
    });

    if (!(window as any).__markflowSessionId || (window as any).__markflowSessionId !== currentSessionId) {
        emitToIntelliJLog(
            `MARKFLOW_UI recovery:notify${status}:sessionMismatch sessionId=${currentSessionId} current=${(window as any).__markflowSessionId}`
        );
        clearRecoveryState(`notify:${status}:sessionChanged`);
        return;
    }

    emitToIntelliJLog(
        `MARKFLOW_UI recovery:notify${status} epoch=${epoch} sessionId=${currentSessionId} reason=${reason}`
    );

    (window as any).cefQuery({
        request,
        onSuccess: (response: string) => {
            if ((window as any).__markflowSessionId === currentSessionId) {
                emitToIntelliJLog(`MARKFLOW_UI recovery:${status}:ack ${response ?? "<empty>"} sessionId=${currentSessionId}`);
                clearRecoveryState(`notify:${status}:ack`);
            } else {
                emitToIntelliJLog(`MARKFLOW_UI recovery:${status}:ackIgnored sessionChanged during response`);
            }
        },
        onFailure: (_errCode: any, errMsg: string) => {
            emitToIntelliJLog(`MARKFLOW_UI recovery:${status}:ackFailed ${errMsg} sessionId=${currentSessionId}`);
            clearRecoveryState(`notify:${status}:failed`);
        }
    });
}

export function requestRecoveryLease(reason: string): Promise<void> {
    if (!(window as any).cefQuery) {
        clearRecoveryState("request:bridgeMissing");
        return Promise.resolve();
    }

    app.recoveryRequestInFlight = true;
    const currentSessionId = (window as any).__markflowSessionId;
    const request = JSON.stringify({
        action: "recovery:request",
        sessionId: currentSessionId,
        reason
    });

    const RECOVERY_REQUEST_TIMEOUT_MS = 3000;
    emitToIntelliJLog(
        `MARKFLOW_UI recovery:request reason=${reason} sessionId=${currentSessionId} timeout=${RECOVERY_REQUEST_TIMEOUT_MS}ms`
    );

    return new Promise((resolve) => {
        let timeoutHandle: number | null = null;
        let completed = false;

        const cleanup = () => {
            completed = true;
            if (timeoutHandle !== null) {
                window.clearTimeout(timeoutHandle);
                timeoutHandle = null;
            }
        };

        timeoutHandle = window.setTimeout(() => {
            if (completed) return;
            cleanup();
            emitToIntelliJLog(`MARKFLOW_UI recovery:request:timeout after ${RECOVERY_REQUEST_TIMEOUT_MS}ms sessionId=${currentSessionId}`);
            app.recoveryRequestInFlight = false;
            clearRecoveryState("request:timeout");
            resolve();
        }, RECOVERY_REQUEST_TIMEOUT_MS);

        (window as any).cefQuery?.({
            request,
            onSuccess: (response: string) => {
                if (completed) return;
                cleanup();
                try {
                    const parsed = response ? (JSON.parse(response) as RecoveryBridgeResponse) : {};
                    const sessionId = (window as any).__markflowSessionId;

                    if (sessionId !== currentSessionId) {
                        emitToIntelliJLog(
                            `MARKFLOW_UI recovery:request:sessionMismatch oldSession=${currentSessionId} newSession=${sessionId}`
                        );
                        clearRecoveryState("request:sessionChanged");
                        resolve();
                        return;
                    }

                    app.activeRecoveryRole = parsed.role === "leader" || parsed.role === "follower" ? parsed.role : null;
                    app.activeRecoveryEpoch = typeof parsed.epoch === "number" ? parsed.epoch : null;
                    app.recoveryRequestInFlight = false;

                    emitToIntelliJLog(
                        `MARKFLOW_UI recovery:request:success role=${app.activeRecoveryRole} epoch=${app.activeRecoveryEpoch} sessionId=${sessionId}`
                    );
                } catch (error) {
                    emitToIntelliJLog(`MARKFLOW_UI recovery:request:parseFailed ${String(error)}`);
                    clearRecoveryState("request:parseFailed");
                }
                resolve();
            },
            onFailure: (_errCode: any, errMsg: string) => {
                if (completed) return;
                cleanup();
                emitToIntelliJLog(`MARKFLOW_UI recovery:request:failed ${errMsg} sessionId=${currentSessionId}`);
                clearRecoveryState("request:failed");
                resolve();
            }
        });
    });
}
