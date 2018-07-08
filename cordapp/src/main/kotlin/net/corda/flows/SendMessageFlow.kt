package net.corda.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.contracts.MessageContract
import net.corda.core.contracts.Command
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.states.MessageState

@InitiatingFlow
@StartableByRPC
class SendMessageFlow(private val message: MessageState) : FlowLogic<SignedTransaction>() {

    private companion object {
        object CREATING : ProgressTracker.Step("Creating")
        object VERIFYING : ProgressTracker.Step("Verifying")
        object SIGNING : ProgressTracker.Step("Signing")
        object COUNTERPARTY : ProgressTracker.Step("Sending to Counterparty")
        object FINALISING : ProgressTracker.Step("Finalising")

        private fun tracker() = ProgressTracker(CREATING, VERIFYING, SIGNING, COUNTERPARTY, FINALISING)
    }

    override val progressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {
        val stx = collectSignature(verifyAndSign(transaction()))
        progressTracker.currentStep = FINALISING
        return subFlow(FinalityFlow(stx))
    }

    @Suspendable
    private fun collectSignature(
        transaction: SignedTransaction
    ): SignedTransaction {
        val session = initiateFlow(message.recipient)
        progressTracker.currentStep = COUNTERPARTY
        return subFlow(CollectSignaturesFlow(transaction, listOf(session)))
    }

    private fun verifyAndSign(transaction: TransactionBuilder): SignedTransaction {
        progressTracker.currentStep = VERIFYING
        transaction.verify(serviceHub)
        progressTracker.currentStep = SIGNING
        return serviceHub.signInitialTransaction(transaction)
    }

    private fun transaction() =
        TransactionBuilder(notary()).apply {
            progressTracker.currentStep = CREATING
            addOutputState(message, MessageContract.CONTRACT_ID)
            addCommand(
                Command(
                    MessageContract.Commands.Send(),
                    message.participants.map(Party::owningKey)
                )
            )
        }

    private fun notary() = serviceHub.networkMapCache.notaryIdentities.first()
}

/**
 * This class responds to requests sent from the {@link TradeInputFlow}.
 */
@InitiatedBy(SendMessageFlow::class)
class SendMessageResponder(val session: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(object : SignTransactionFlow(session) {
            override fun checkTransaction(stx: SignedTransaction) {}
        })
    }
}