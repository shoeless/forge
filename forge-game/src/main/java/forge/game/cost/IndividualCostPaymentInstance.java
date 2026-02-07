package forge.game.cost;

import java.util.concurrent.atomic.AtomicInteger;

import forge.game.IIdentifiable;

public class IndividualCostPaymentInstance implements IIdentifiable {
    private static final AtomicInteger maxId = new AtomicInteger(0);
    private static int nextId() { return maxId.incrementAndGet(); }

    private final int id;
    private final CostPart cost;
    private final CostPayment payment;

    public IndividualCostPaymentInstance(final CostPart cost, final CostPayment payment) {
        id = nextId();
        this.cost = cost;
        this.payment = payment;
    }

    public int getId() { return id; }

    public CostPart getCost() { return cost; }
    public CostPayment getPayment() { return payment; }

}
