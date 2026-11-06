package it.eone.ewf.engine.spi;

/** Un handler fa una cosa sul backend e restituisce un esito. Deve essere idempotente sulla chiave. */
@FunctionalInterface
public interface Handler {
    HandlerResult execute(HandlerContext context);
}
