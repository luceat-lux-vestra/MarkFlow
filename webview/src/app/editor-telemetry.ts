export const emitToIntelliJLog = (message: string) => {
    const logger = window.markflowLog;
    if (typeof logger !== "function") return;
    try {
        logger(message);
    } catch {
        // Ignore diagnostics bridge failures so editor boot is unaffected.
    }
};

let diagnosticsLoggingEnabled: boolean | null = null;

const isDiagnosticsLoggingEnabled = () => {
    if (diagnosticsLoggingEnabled !== null) {
        return diagnosticsLoggingEnabled;
    }

    diagnosticsLoggingEnabled = window.__markflowDiagnosticsEnabled === true;

    return diagnosticsLoggingEnabled;
};

export const emitDiagnosticsLog = (message: string, emit: (message: string) => void) => {
    if (isDiagnosticsLoggingEnabled()) {
        emit(message);
    }
};

export const markFlowStage = (stage: string, emit: (message: string) => void, detail = "") => {
    const message = detail ? `MARKFLOW_UI ${stage}: ${detail}` : `MARKFLOW_UI ${stage}`;
    emitDiagnosticsLog(message, emit);
    const app = document.getElementById("app");
    if (app) {
        app.setAttribute("data-markflow-stage", stage);
    }
};

export const logMermaidTrace = (detail: string, emit: (message: string) => void) => {
    const line = `MARKFLOW_UI mermaid:${detail}`;
    emitDiagnosticsLog(line, emit);
};

export const showBootError = (stage: string, detail: string, emit: (message: string) => void) => {
    emit(`MARKFLOW_UI bootError ${stage}: ${detail}`);
    const app = document.getElementById("app");
    if (!app) return;
    app.innerHTML = `
      <div style="font-family: sans-serif; padding: 16px; color: #b91c1c; background: #fff1f2; border: 1px solid #fecdd3;">
        <div style="font-weight: 700; margin-bottom: 8px;">MarkFlow UI failed to boot</div>
        <div><b>stage:</b> ${stage}</div>
        <div><b>detail:</b> ${detail}</div>
      </div>
    `;
};
