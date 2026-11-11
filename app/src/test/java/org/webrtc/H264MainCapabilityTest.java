package org.webrtc;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class H264MainCapabilityTest {
  private static VideoCodecInfo baseline() { return new VideoCodecInfo("H264",H264Utils.getDefaultH264Params(false),Collections.emptyList()); }
  private static VideoCodecInfo main() { return new VideoCodecInfo("H264",H264MainSupport.parameters(42),Collections.emptyList()); }
  private static class Base implements VideoDecoderFactory {
    VideoCodecInfo requested;
    public VideoCodecInfo[] getSupportedCodecs() { return new VideoCodecInfo[]{baseline(),new VideoCodecInfo("H264",H264Utils.getDefaultH264Params(true),Collections.emptyList())}; }
    public VideoDecoder createDecoder(VideoCodecInfo codec) { requested=codec;return null; }
  }
  @Test public void mainRequiresAnExplicitCapabilityAndKeepsTheOriginalBaselineUnmodified() {
    Base base=new Base();
    H264VideoDecoderFactory ordinary=new H264VideoDecoderFactory(base,false);
    assertEquals(2,ordinary.getSupportedCodecs().length);
    ordinary.createDecoder(main());assertNull(base.requested);
    H264VideoDecoderFactory capable=new H264VideoDecoderFactory(base,false,main());
    VideoCodecInfo[] offered=capable.getSupportedCodecs();
    assertEquals(3,offered.length);assertEquals("4d002a",offered[0].params.get("profile-level-id"));
    assertEquals("42e01f",offered[1].params.get("profile-level-id"));
    capable.createDecoder(offered[0]);assertSame(offered[0],base.requested);
    H264VideoDecoderFactory light=new H264VideoDecoderFactory(base,true,main());
    assertEquals(1,light.getSupportedCodecs().length);assertEquals("42e01f",light.getSupportedCodecs()[0].params.get("profile-level-id"));
  }
  @Test public void mainLevelsReflectMacroblockRateAndNeverInvent4k60Support() {
    assertEquals(31,H264MainSupport.requiredLevel(1280,720,30));
    assertEquals(32,H264MainSupport.requiredLevel(1280,720,60));
    assertEquals(40,H264MainSupport.requiredLevel(1920,1080,30));
    assertEquals(42,H264MainSupport.requiredLevel(1920,1080,60));
    assertEquals(51,H264MainSupport.requiredLevel(2560,1440,60));
    assertEquals(-1,H264MainSupport.requiredLevel(3840,2160,60));
    assertEquals(-1,H264MainSupport.requiredLevel(0,1080,60));
    assertEquals(-1,H264MainSupport.requiredLevel(Integer.MAX_VALUE,1080,60));
    Map<String,String> p=H264MainSupport.parameters(42);
    assertEquals("4d002a",p.get("profile-level-id"));assertEquals("1",p.get("packetization-mode"));
    assertEquals("42e01f",baseline().params.get("profile-level-id"));
  }
}
