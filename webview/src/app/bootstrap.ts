import "@milkdown/crepe/theme/common/style.css";
import "@milkdown/crepe/theme/frame.css";
import "../style.css";
import "../styles/mermaid.css";

import {MarkFlowEditorSession} from "./editor-session";

export const bootstrapMarkFlowEditor = async () => {
    const session = new MarkFlowEditorSession();
    await session.initEditor();
};
