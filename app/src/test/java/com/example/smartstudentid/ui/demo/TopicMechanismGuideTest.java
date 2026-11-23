package com.example.smartstudentid.ui.demo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/**
 * Unit tests for {@link TopicMechanismGuide}: ensures all 5 topics (§1–§5) provide distinct,
 * non-empty Before and After mechanism explanations and badges.
 */
public class TopicMechanismGuideTest {

    @Test
    public void allFiveTopicsProvideDistinctBeforeAndAfterMechanismGuides() {
        for (int topic = 0; topic < 5; topic++) {
            TopicMechanismGuide.MechanismInfo before = TopicMechanismGuide.forTopic(topic, false);
            TopicMechanismGuide.MechanismInfo after = TopicMechanismGuide.forTopic(topic, true);

            assertNotNull(before);
            assertNotNull(after);
            assertFalse(before.headline.isEmpty());
            assertFalse(before.whatItDoes.isEmpty());
            assertFalse(before.whyItImpacts.isEmpty());
            assertFalse(before.primaryBadge.isEmpty());
            assertFalse(before.secondaryBadge.isEmpty());
            assertFalse(before.comparisonMechanismLabel.isEmpty());

            assertFalse(after.headline.isEmpty());
            assertFalse(after.whatItDoes.isEmpty());
            assertFalse(after.whyItImpacts.isEmpty());
            assertFalse(after.primaryBadge.isEmpty());
            assertFalse(after.secondaryBadge.isEmpty());
            assertFalse(after.comparisonMechanismLabel.isEmpty());

            assertNotEquals(before.headline, after.headline);
            assertNotEquals(before.whatItDoes, after.whatItDoes);
            assertNotEquals(before.comparisonMechanismLabel, after.comparisonMechanismLabel);
        }
    }
}
