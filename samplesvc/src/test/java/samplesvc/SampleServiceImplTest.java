package samplesvc;

import java.io.File;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import uk.gov.ons.ctp.response.party.definition.Party;
import uk.gov.ons.ctp.response.party.definition.PartyAttributeMap;
import uk.gov.ons.ctp.response.sample.definition.BusinessSampleUnit;
import uk.gov.ons.ctp.response.sample.definition.BusinessSurveySample;

public class SampleServiceImplTest {

  @Test
  public void test() throws Exception {
    File file = new File("src/test/resources/uk/gov/ons/ctp/response/sample/service/impl/business-survey-sample.xml");
    JAXBContext jaxbContext = JAXBContext.newInstance(BusinessSurveySample.class);

    Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();
    BusinessSurveySample sample = (BusinessSurveySample) jaxbUnmarshaller.unmarshal(file);
    for (BusinessSampleUnit unit : sample.getSampleUnits().getBusinessSampleUnits()) {
      Party p = new Party();
      p.setSampleUnitType(unit.getSampleUnitType());
      p.setSampleUnitRef(unit.getSampleUnitRef());
      PartyAttributeMap attribs = new PartyAttributeMap();
      attribs.put("businessRegion", unit.getBusinessRegion());
      attribs.put("frosic2003", unit.getFrosic2003());
      p.setAttributes(attribs);

      ObjectMapper mapper = new ObjectMapper();
      System.out.println(mapper.writeValueAsString(p));
    }
  }
}