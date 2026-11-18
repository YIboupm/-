package com.compras.procurement.review;

import com.compras.procurement.budget.BudgetService;
import com.compras.procurement.llm.*;
import com.compras.procurement.receiving.AssetClassifier;
import com.compras.procurement.research.WebResearch;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.*;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
 "server.address=127.0.0.1","spring.data.mongodb.uri=mongodb://127.0.0.1:${qa.mongo.port:27028}/lifecycle_${random.uuid}",
 "spring.config.import=","llm.api-key=qa-unused","procurement.consolidation.cron=-"})
class ProcurementLifecycleIT {
 @Autowired TestRestTemplate client;
 @Autowired ObjectMapper json;
 @Autowired BudgetService budget;
 @Autowired com.compras.procurement.intake.ConversationRepository conversations;
 boolean pricedFixture=false;
 @MockitoBean ModelGateway model;
 @MockitoBean WebResearch research;
 @BeforeEach void setup(){
   when(model.call(anyString(),anyString(),anyList(),eq(AssetClassifier.Reply.class)))
     .thenReturn(new AssetClassifier.Reply("FURNITURE","QA furniture fixture"));
 }
 ResponseEntity<JsonNode> call(String path,String who,Object body,HttpMethod method){
   var h=new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON);
   if(who!=null) h.set("X-Staff-Id",who);
   return client.exchange("/api/"+path,method,new HttpEntity<>(body,h),JsonNode.class);
 }
 JsonNode ok(ResponseEntity<JsonNode> res){ assertTrue(res.getStatusCode().is2xxSuccessful(),res::toString); return res.getBody(); }
 JsonNode post(String path,String who,Object body){return ok(call(path,who,body,HttpMethod.POST));}
 JsonNode get(String path,String who){return ok(call(path,who,null,HttpMethod.GET));}
 String text(JsonNode n,String k){return n.path(k).asText();}
 List<JsonNode> rows(JsonNode a){return StreamSupport.stream(a.spliterator(),false).toList();}
 String submit(boolean standard,int qty) throws Exception {
   var c=post("intake/conversations",null,Map.of("employeeId","E002"));
   var base="intake/conversations/"+text(c,"id");
   Map<String,Object> deskItem=new HashMap<>(Map.of("name","QA desk","quantity",qty,"requirements",List.of()));
   if(pricedFixture){
     var conversation=conversations.findById(text(c,"id")).orElseThrow();
     conversation.addSource("https://qa.invalid/desk","Synthetic desk price EUR 100","fixture",java.time.Instant.now());
     conversations.save(conversation);
     deskItem.put("reference_unit_price",100);deskItem.put("reference_url","https://qa.invalid/desk");
   }
   var items=List.of(deskItem,Map.of("name","QA chair","quantity",qty,"requirements",List.of()));
   Object args=standard?Map.of("category","LAPTOP","fields",Map.of("item","LAP-STD","quantity",qty,"neededBy",LocalDate.now().plusDays(40).toString(),"deliverySite","Madrid HQ","justification","QA laptops for engineering"),"summary","QA laptops"):
     Map.of("title","QA desk and chair","description","QA new office furniture","quantity",qty,"reasons",List.of("No suitable furniture available"),"requirements",List.of(),"items",items,"needed_by",LocalDate.now().plusDays(40).toString());
   var t=new ToolCall("test-call",standard?"propose_standard_request":"propose_special_request",json.writeValueAsString(args));
   when(model.chat(anyString(),anyString(),anyList(),anyList())).thenReturn(new ChatResult(null,List.of(t),null,"tool_calls",Map.of()),new ChatResult("Confirmed.",List.of(),null,"stop",Map.of()));
   var card=post(base+"/messages",null,Map.of("text",standard?"Request standard laptops":"Request desks and chairs"));
   assertEquals("AWAITING_CONFIRMATION",text(card,"status"),card::toString);
   assertEquals(0,card.path("submitted").size());
   var confirmed=post(base+"/proposal/confirm",null,Map.of());
   var sub=confirmed.path("submitted").get(0);assertNotNull(sub,confirmed::toString);
   System.out.println("QA REQUEST "+sub); return text(sub,"requestId");
 }
 JsonNode pr(String id){return get("requests/"+id,null);}
 JsonNode pending(String id){return rows(pr(id).path("approvals")).stream().filter(a->text(a,"status").equals("PENDING")).findFirst().orElseThrow();}
 void decision(String id,boolean yes){post("approvals/"+id+"/decide",text(pending(id),"approverId"),Map.of("approve",yes,"note","QA decision"));}
 void approvals(String id){for(int i=0;i<9 && text(pr(id),"status").equals("PENDING_APPROVAL");i++) decision(id,true);}
 void state(String id,String expected){assertEquals(expected,text(pr(id),"status"));}
 String buyer(String id){return "buyer/requests/"+id+"/";}
 void quotes(String id,int qty,double desk,double chair,String who){
   for(int i=0;i<2;i++)post(buyer(id)+"quotes",who,Map.of("supplierName","QA vendor "+i,"itemId",""+(i+1),"description",i==0?"QA desk":"QA chair","quantity",qty,"unitPrice",i==0?desk:chair,"reference","QA-"+i));
 }
 void award(String id,String who){
   var q=pr(id).path("quotes");int i=q.size()-2;
   post(buyer(id)+"award",who,Map.of("lines",List.of(Map.of("itemId","1","quoteIndex",i),Map.of("itemId","2","quoteIndex",i+1)),"note","QA award"));
 }
 List<JsonNode> receipts(String id,String who){return rows(get("receiving",who)).stream().filter(r->rows(r.path("lines")).stream().anyMatch(l->id.equals(text(l,"requestId")))).toList();}
 List<JsonNode> assets(String id){return rows(get("assets",null)).stream().filter(a->id.equals(text(a.path("acquisition"),"requestId"))).toList();}
 String checkPath(JsonNode r){return "receiving/"+text(r,"id")+"/check-in";}
 JsonNode check(JsonNode r,String who,int n,int rejected,List<String> serials){return post(checkPath(r),who,Map.of("quantity",n,"rejected",rejected,"rejectionReason","Transit damage","serialNumbers",serials));}
 void startBuy(String id){decision(id,true);state(id,"BUYER_REVIEW");post(buyer(id)+"accept","S40",Map.of());}
 @Test void standardItPartialAndAssets() throws Exception {
   var id=submit(true,2);assertEquals(List.of("LINE_MANAGER","IT_ASSET_MANAGER"),rows(pr(id).path("approvals")).stream().map(a->text(a,"role")).toList());
   approvals(id);state(id,"POOLED");assertTrue(budget.position(id).reserved()>0);
   post("consolidation/run?asOf="+LocalDate.now().plusDays(70),null,Map.of());state(id,"ORDERED");
   var r=receipts(id,"S30").get(0);
   assertTrue(call(checkPath(r),"S30",Map.of("quantity",1,"serialNumbers",List.of()),HttpMethod.POST).getStatusCode().is4xxClientError());
   check(r,"S30",1,1,List.of("QA-"+id+"-1"));state(id,"ORDERED");assertEquals(1,assets(id).size());
   check(r,"S30",1,0,List.of("QA-"+id+"-2"));state(id,"RECEIVED");assertEquals(2,assets(id).size());assertEquals(0,budget.position(id).committed());
 }
 @Test void rejectAndCancel() throws Exception {
   var reject=submit(false,1);decision(reject,false);state(reject,"REJECTED");assertEquals(0,budget.position(reject).reserved());assertTrue(receipts(reject,"S50").isEmpty());
   var cancel=submit(true,1);approvals(cancel);state(cancel,"POOLED");assertTrue(budget.position(cancel).reserved()>0);
   post("requests/"+cancel+"/cancel",null,Map.of("employeeId","E002","reason","QA withdrawn"));state(cancel,"CANCELLED");assertEquals(0,budget.position(cancel).reserved());assertTrue(receipts(cancel,"S30").isEmpty());
 }
 @Test void correctedAwardReopenAndReceive() throws Exception {
   var id=submit(false,2);startBuy(id);quotes(id,2,140,100,"S40");award(id,"S40");
   post("approvals/"+id+"/send-back",text(pending(id),"approverId"),Map.of("note","Revise specification"));state(id,"SOURCING");
   assertTrue(rows(pr(id).path("approvals")).stream().anyMatch(a->text(a,"stage").equals("NEED")&&text(a,"status").equals("APPROVED")));
   quotes(id,2,145,100,"S40");award(id,"S40");assertEquals("SPEND",text(pending(id),"stage"));approvals(id);state(id,"ORDERED");
   var old=receipts(id,"S50");assertEquals(2,old.size());assertEquals(49000,budget.position(id).committed());
   post(buyer(id)+"reopen","S40",Map.of("reason","Supplier corrected units"));state(id,"SOURCING");assertTrue(receipts(id,"S50").isEmpty());
   System.out.println("QA reopen position "+budget.position(id));
   assertEquals(0,budget.position(id).committed());
   quotes(id,2,150,100,"S40");award(id,"S40");approvals(id);state(id,"ORDERED");
   var now=receipts(id,"S50");assertEquals(2,now.size());assertTrue(old.stream().noneMatch(o->now.stream().anyMatch(n->text(o,"orderNumber").equals(text(n,"orderNumber")))));
   var desk=now.stream().filter(r->r.path("unitPrice").asInt()==150).findFirst().orElseThrow();var chair=now.stream().filter(r->r.path("unitPrice").asInt()==100).findFirst().orElseThrow();
   check(desk,"S50",1,1,List.of());state(id,"ORDERED");
   assertTrue(call(buyer(id)+"reopen","S40",Map.of("reason","QA"),HttpMethod.POST).getStatusCode().is4xxClientError());
   check(chair,"S50",2,0,List.of());state(id,"ORDERED");check(desk,"S50",1,0,List.of());state(id,"RECEIVED");
   assertEquals(2,assets(id).size());assertTrue(assets(id).stream().allMatch(a->text(a,"status").equals("RESERVED")));
   assertEquals(0,budget.position(id).committed());
   assertEquals(50000,budget.account("Engineering").journal().stream().filter(e->id.equals(e.requestId())&&e.type().name().equals("SPEND")).mapToLong(e->e.amount()).sum());
 }
 @Test void strategicTenderThroughReceipt() throws Exception {
   var id=submit(false,1);post("approvals/"+id+"/strategic",text(pending(id),"approverId"),Map.of("note","Complex furniture contract"));state(id,"STRATEGIC_REVIEW");
   post("strategic/"+id+"/decide","S41",Map.of("outcome","SOURCE","note","Request firm quotes"));
   if(text(pr(id),"status").equals("BUYER_REVIEW"))post(buyer(id)+"accept","S41",Map.of());
   state(id,"SOURCING");quotes(id,1,151,90,"S41");award(id,"S41");approvals(id);state(id,"ORDERED");
   var rs=receipts(id,"S50");assertEquals(2,rs.size());for(var r:rs)check(r,"S50",1,0,List.of());
   state(id,"RECEIVED");assertEquals(1,assets(id).size());
 }
 @Test void confirmedQuantitiesShouldBeFulfilled() throws Exception {
   var id=submit(false,2);startBuy(id);quotes(id,1,150,100,"S40");
   var body=Map.of("lines",List.of(Map.of("itemId","1","quoteIndex",0),Map.of("itemId","2","quoteIndex",1)));
   var shortQuote=call(buyer(id)+"award","S40",body,HttpMethod.POST);
   assertTrue(shortQuote.getStatusCode().is4xxClientError());state(id,"SOURCING");
   assertTrue(receipts(id,"S50").isEmpty());
   quotes(id,2,150,100,"S40");award(id,"S40");approvals(id);
   var rs=receipts(id,"S50");assertEquals(2,rs.size());
   for(var r:rs)check(r,"S50",1,0,List.of());
   state(id,"ORDERED");assertEquals(2,pr(id).path("delivery").path("received").asInt());
   for(var r:rs)check(r,"S50",1,0,List.of());
   state(id,"RECEIVED");assertEquals(4,pr(id).path("delivery").path("received").asInt());
 }
 @Test void priceDeviationJustOverFortyRequiresExplanation() throws Exception {
   pricedFixture=true;var id=submit(false,1);startBuy(id);quotes(id,1,140.40,100,"S40");
   var body=Map.of("lines",List.of(Map.of("itemId","1","quoteIndex",0),Map.of("itemId","2","quoteIndex",1)));
   var res=call(buyer(id)+"award","S40",body,HttpMethod.POST);
   System.out.println("QA 40.4 percent result "+res.getStatusCode()+" status "+text(pr(id),"status"));
   assertTrue(res.getStatusCode().is4xxClientError(),"Expected a written explanation for 40.4% above EUR 100");
   quotes(id,1,59.99,100,"S40");
   var low=Map.of("lines",List.of(Map.of("itemId","1","quoteIndex",2),Map.of("itemId","2","quoteIndex",3)));
   assertTrue(call(buyer(id)+"award","S40",low,HttpMethod.POST).getStatusCode().is4xxClientError());
   quotes(id,1,140.01,100,"S40");
   var high=Map.of("lines",List.of(Map.of("itemId","1","quoteIndex",4),Map.of("itemId","2","quoteIndex",5)));
   assertTrue(call(buyer(id)+"award","S40",high,HttpMethod.POST).getStatusCode().is4xxClientError());
   quotes(id,1,140,100,"S40");award(id,"S40");state(id,"PENDING_APPROVAL");
   post("approvals/"+id+"/send-back",text(pending(id),"approverId"),Map.of("note","QA documented replacement"));
   quotes(id,1,140.40,100,"S40");
   int last=pr(id).path("quotes").size()-1;
   var documented=Map.of("lines",List.of(Map.of("itemId","1","quoteIndex",last-1,"priceNote","Additional installation included"),Map.of("itemId","2","quoteIndex",last)));
   post(buyer(id)+"award","S40",documented);state(id,"PENDING_APPROVAL");
 }
 @Test void postReceiptReturn() throws Exception {
   var id=submit(false,1);startBuy(id);quotes(id,1,150,100,"S40");award(id,"S40");approvals(id);
   var rs=receipts(id,"S50");for(var r:rs)check(r,"S50",1,0,List.of());state(id,"RECEIVED");
   var desk=rs.stream().filter(r->r.path("unitPrice").asInt()==150).findFirst().orElseThrow();
   var tag=text(assets(id).get(0),"assetTag");
   var path="receiving/"+text(desk,"id")+"/returns";
   var payload=Map.of("requestId",id,"operationId",UUID.randomUUID().toString(),"quantity",1,"assetTags",List.of(tag),"reason","Fault discovered after unpacking");
   assertTrue(call(path,"S30",payload,HttpMethod.POST).getStatusCode().is4xxClientError());
   for(int i=0;i<2;i++) assertEquals(1,post(path,"S50",payload).path("supplierReturns").size());
   state(id,"ORDERED");assertEquals(25000,budget.position(id).committed());
   assertEquals("RETURNED",text(assets(id).get(0),"status"));assertEquals(0,pr(id).path("delivery").path("assetTags").size());
   var chair=rs.stream().filter(r->r.path("unitPrice").asInt()==100).findFirst().orElseThrow();
   var untracked=Map.of("requestId",id,"operationId",UUID.randomUUID().toString(),"quantity",1,"assetTags",List.of(),"reason","Replacement requested");
   post("receiving/"+text(chair,"id")+"/returns","S50",untracked);
   assertEquals(25000,budget.position(id).committed());
   check(desk,"S50",1,0,List.of());state(id,"ORDERED");
   check(chair,"S50",1,0,List.of());state(id,"RECEIVED");assertEquals(0,budget.position(id).committed());
   post(path,"S50",payload);state(id,"RECEIVED");assertEquals(2,assets(id).size());
   assertEquals(1,pr(id).path("delivery").path("assetTags").size());
   var journal=budget.account("Engineering").journal().stream().filter(e->id.equals(e.requestId())).toList();
   long spent=journal.stream().filter(e->e.type().name().equals("SPEND")).mapToLong(e->e.amount()).sum();
   long reversed=journal.stream().filter(e->e.type().name().equals("RETURN_TO_COMMITMENT")).mapToLong(e->e.amount()).sum();
   assertEquals(25000,spent-reversed);
 }
}

