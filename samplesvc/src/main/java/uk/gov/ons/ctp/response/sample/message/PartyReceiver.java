package uk.gov.ons.ctp.response.sample.message;

import uk.gov.ons.ctp.response.party.definition.Party;

/**
 * The reader of Party(s) from queue
 */
public interface PartyReceiver {

    /**
     * To process a Party read from queue
     * @param party the java representation of the message body
     */
    void acceptParty(Party party) throws Exception;
}