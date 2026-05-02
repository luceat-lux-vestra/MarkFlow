void import("./app/bootstrap")
    .then(({bootstrapMarkFlowEditor}) => bootstrapMarkFlowEditor())
    .catch((error) => {
        console.error("MARKFLOW_UI bootstrap failed", error);
    });
