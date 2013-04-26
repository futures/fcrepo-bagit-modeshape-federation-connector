
package gov.loc.repository.bagit.v0_96.impl;

import gov.loc.repository.bagit.BagFactory;
import gov.loc.repository.bagit.impl.AbstractBag;

public class BagImpl extends AbstractBag {

    public BagImpl(final BagFactory bagFactory) {
        super(new BagPartFactoryImpl(bagFactory, new BagConstantsImpl()),
                new BagConstantsImpl(), bagFactory);
    }

}
