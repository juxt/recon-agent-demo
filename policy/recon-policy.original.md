# Settlement Reconciliation Policy

**Section 4.3 — Investigation and closure of settlement amount breaks**

*Owner: Operations Risk. Approved by the Operations Risk Committee.
Effective 1 July 2026. Review annually.*

*This is the policy as it stood before the derivation run. Point the loop at
this file to replay it: the questions the prose leaves open are the ones it
will ask.*

---

**4.3.1** A settlement amount break arises where the trade record and the
counterparty's confirmation agree on the quantity traded but differ on the
amount of money to be settled. Breaks are raised by the reconciliation platform
and routed to the team that owns the product.

**4.3.2** A break may be closed where the difference between the two amounts does
not exceed the closure tolerance. The closure tolerance is USD 250.

**4.3.3** A case must be escalated to an authorised analyst where any of the
following holds:

- an amendment to the trade is in flight upstream;
- the counterparty has not confirmed the current version of the trade;
- the account carries an open dispute.

**4.3.4** A break whose difference exceeds the closure tolerance must be
escalated with the full findings of the investigation and no recommended
disposition.

**4.3.5** Closure within tolerance may be proposed by an automated process but
takes effect only on confirmation by an authorised analyst. The analyst confirms
or rejects; they do not delegate that decision back to the process.

**4.3.6** Every closure and every escalation must record the rule applied, the
tolerance in force, the version of this policy under which it was evaluated, and
the identity of the confirming analyst. Records are immutable.
