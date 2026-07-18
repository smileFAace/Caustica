package dev.comfyfluffy.caustica.rt.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class RtEmissionColorTableTest {
	@Test
	void packsRgbChannelsAndNeverReturnsZero() {
		assertEquals(0xFF8000, RtEmissionColorTable.packRgb(1.0f, 0.5f, 0.0f));
		// 0,0,0 would pack to 0; API bumps so GPU "no override" stays reserved for NONE.
		assertNotEquals(0, RtEmissionColorTable.packRgb(0f, 0f, 0f));
		assertEquals(0, RtEmissionColorTable.NONE);
	}
}
