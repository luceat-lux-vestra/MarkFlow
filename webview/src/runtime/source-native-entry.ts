import {installSourceNativeBootstrap, type SourceNativeBootstrapWindow} from "./source-native-bootstrap.ts";

const parent = document.getElementById("app");
if (parent === null) {
    console.error("MARKFLOW_UI source-native bootstrap failed: missing #app root");
} else {
    try {
        installSourceNativeBootstrap(parent, window as SourceNativeBootstrapWindow, window.location.search);
    } catch (error) {
        console.error("MARKFLOW_UI source-native bootstrap failed", error);
    }
}
