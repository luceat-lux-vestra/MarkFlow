import type {RecoveryBridgeResponse, RecoveryRole} from "./types";

type RecoveryControllerState = {
    activeRecoveryEpoch: number | null;
    activeRecoveryRole: RecoveryRole | null;
    recoveryRequestInFlight: boolean;
};

export const createRecoveryController = (emitToIntelliJLog: (message: string) => void) => {
    const state: RecoveryControllerState = {
        activeRecoveryEpoch: null,
        activeRecoveryRole: null,
        recoveryRequestInFlight: false
    };

    const clearRecoveryState = (reason: string) => {
        if (state.activeRecoveryEpoch === null && state.activeRecoveryRole === null && !state.recoveryRequestInFlight) {
            return;
        }

        emitToIntelliJLog(
            `MARKFLOW_UI recovery:clear reason=${reason} epoch=${state.activeRecoveryEpoch ?? -1} role=${state.activeRecoveryRole ?? "none"} sessionId=${window.__markflowSessionId ?? "unknown"}`
        );
        state.activeRecoveryEpoch = null;
        state.activeRecoveryRole = null;
        state.recoveryRequestInFlight = false;
    };

    const notifyRecoveryOutcome = (status: "complete" | "failed", epoch: number, reason: string) => {
        if (!window.cefQuery) {
            clearRecoveryState(`notify:${status}:bridgeMissing`);
            return;
        }

        const currentSessionId = window.__markflowSessionId;
        const request = JSON.stringify({
            action: `recovery:${status}`,
            sessionId: currentSessionId,
            epoch,
            reason
        });

        if (!window.__markflowSessionId || window.__markflowSessionId !== currentSessionId) {
            emitToIntelliJLog(
                `MARKFLOW_UI recovery:notify${status}:sessionMismatch sessionId=${currentSessionId} current=${window.__markflowSessionId}`
            );
            clearRecoveryState(`notify:${status}:sessionChanged`);
            return;
        }

        emitToIntelliJLog(
            `MARKFLOW_UI recovery:notify${status} epoch=${epoch} sessionId=${currentSessionId} reason=${reason}`
        );

        window.cefQuery({
            request,
            onSuccess: (response) => {
                if (window.__markflowSessionId === currentSessionId) {
                    emitToIntelliJLog(`MARKFLOW_UI recovery:${status}:ack ${response ?? "<empty>"} sessionId=${currentSessionId}`);
                    clearRecoveryState(`notify:${status}:ack`);
                } else {
                    emitToIntelliJLog(`MARKFLOW_UI recovery:${status}:ackIgnored sessionChanged during response`);
                }
            },
            onFailure: (_errCode, errMsg) => {
                emitToIntelliJLog(`MARKFLOW_UI recovery:${status}:ackFailed ${errMsg} sessionId=${currentSessionId}`);
                clearRecoveryState(`notify:${status}:failed`);
            }
        });
    };

    const requestRecoveryLease = (reason: string): Promise<void> => {
        if (!window.cefQuery) {
            clearRecoveryState("request:bridgeMissing");
            return Promise.resolve();
        }

        state.recoveryRequestInFlight = true;
        const currentSessionId = window.__markflowSessionId;
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
                state.recoveryRequestInFlight = false;
                clearRecoveryState("request:timeout");
                resolve();
            }, RECOVERY_REQUEST_TIMEOUT_MS);

            window.cefQuery?.({
                request,
                onSuccess: (response) => {
                    if (completed) return;
                    cleanup();
                    try {
                        const parsed = response ? (JSON.parse(response) as RecoveryBridgeResponse) : {};
                        const sessionId = window.__markflowSessionId;

                        if (sessionId !== currentSessionId) {
                            emitToIntelliJLog(
                                `MARKFLOW_UI recovery:request:sessionMismatch oldSession=${currentSessionId} newSession=${sessionId}`
                            );
                            clearRecoveryState("request:sessionChanged");
                            resolve();
                            return;
                        }

                        state.activeRecoveryRole = parsed.role === "leader" || parsed.role === "follower" ? parsed.role : null;
                        state.activeRecoveryEpoch = typeof parsed.epoch === "number" ? parsed.epoch : null;
                        state.recoveryRequestInFlight = false;

                        emitToIntelliJLog(
                            `MARKFLOW_UI recovery:request:success role=${state.activeRecoveryRole} epoch=${state.activeRecoveryEpoch} sessionId=${sessionId}`
                        );
                    } catch (error) {
                        emitToIntelliJLog(`MARKFLOW_UI recovery:request:parseFailed ${String(error)}`);
                        clearRecoveryState("request:parseFailed");
                    }
                    resolve();
                },
                onFailure: (_errCode, errMsg) => {
                    if (completed) return;
                    cleanup();
                    emitToIntelliJLog(`MARKFLOW_UI recovery:request:failed ${errMsg} sessionId=${currentSessionId}`);
                    clearRecoveryState("request:failed");
                    resolve();
                }
            });
        });
    };

    return {
        state,
        clearRecoveryState,
        notifyRecoveryOutcome,
        requestRecoveryLease
    };
};
