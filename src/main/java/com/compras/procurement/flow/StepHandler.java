package com.compras.procurement.flow;

import com.compras.procurement.policy.PolicySet;
import com.compras.procurement.request.FlowStep;
import com.compras.procurement.request.PurchaseRequest;

/**
 * One building block of a route. A policy version combines these into routes; adding a new kind of step means
 * adding one implementation of this interface, nothing else.
 */
public interface StepHandler {

    enum Outcome {
        /** The step is finished; the engine goes on to the next one. */
        DONE,
        /** Someone has to act (approve, source, decide); the engine continues when they have. */
        WAITING
    }

    record Context(PurchaseRequest request, FlowStep step, PolicySet policy) {
    }

    /** The step type this handler implements, as routes name it. */
    String type();

    /** The request arrives at this step. */
    Outcome enter(Context context);

    /** Someone acted while the request was waiting at this step: is it finished now? */
    default Outcome resume(Context context) {
        return enter(context);
    }
}
