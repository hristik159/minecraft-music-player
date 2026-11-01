package dev.krys.blockradio;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
class PlaybackTest {
 Track track(String id){return new Track("yandex",id,"title","artist","",120000);}
 @BeforeEach void reset(){RadioServer.current=null;RadioServer.queue.clear();RadioServer.history.clear();RadioServer.paused=false;RadioServer.autoplay=true;RadioServer.requesting=false;}
 @Test void queueWinsBeforeRecommendations(){RadioServer.play(track("1"),false);RadioServer.queue.add(track("2"));RadioServer.next();assertEquals("2",RadioServer.current.id());assertFalse(RadioServer.requesting);assertEquals("1",RadioServer.history.getLast().id());}
 @Test void emptyQueueRequestsRecommendationAndFreezesClock(){RadioServer.play(track("1"),false);RadioServer.base=12345;RadioServer.next();assertTrue(RadioServer.requesting);assertTrue(RadioServer.paused);assertTrue(RadioServer.position()>=12345);}
 @Test void historyIsBounded(){for(int i=0;i<200;i++)RadioServer.play(track(Integer.toString(i)),true);assertEquals(50,RadioServer.history.size());}
 @Test void rejectsUnexpectedProvidersAndInvalidIds(){JsonObject j=new JsonObject();j.addProperty("provider","youtube");j.addProperty("id","../../file");assertThrows(IllegalArgumentException.class,()->Track.parse(j));j.addProperty("provider","unknown");assertThrows(IllegalArgumentException.class,()->Track.parse(j));}
 @Test void youtubeMetadataRoundtrip(){Track t=new Track("youtube","abcdefghijk","title","channel","",50000);assertEquals(t,Track.parse(t.json()));}
}
