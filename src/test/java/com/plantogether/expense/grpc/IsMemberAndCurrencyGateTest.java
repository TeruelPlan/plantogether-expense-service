package com.plantogether.expense.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plantogether.common.grpc.TripClient;
import com.plantogether.common.grpc.TripGrpcClient;
import com.plantogether.expense.controller.ExpenseController;
import com.plantogether.expense.domain.Expense;
import com.plantogether.expense.domain.RateSource;
import com.plantogether.expense.exception.GlobalExceptionHandler;
import com.plantogether.expense.fx.ExchangeRateProvider;
import com.plantogether.expense.fx.ExchangeRateProvider.FxQuote;
import com.plantogether.expense.repository.ExpenseRepository;
import com.plantogether.expense.service.ExpenseService;
import com.plantogether.trip.grpc.GetTripCurrencyRequest;
import com.plantogether.trip.grpc.GetTripCurrencyResponse;
import com.plantogether.trip.grpc.GetTripMembersRequest;
import com.plantogether.trip.grpc.GetTripMembersResponse;
import com.plantogether.trip.grpc.IsMemberRequest;
import com.plantogether.trip.grpc.IsMemberResponse;
import com.plantogether.trip.grpc.TripMemberProto;
import com.plantogether.trip.grpc.TripServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * In-process gRPC integration test exercising both {@code isMember} (via {@code requireMembership})
 * and {@code getTripCurrency} through the shared {@link com.plantogether.common.grpc.TripClient}.
 */
class IsMemberAndCurrencyGateTest {

  private static final String DEVICE_ID = UUID.randomUUID().toString();
  private static final UUID TRIP_ID = UUID.randomUUID();
  private static final UUID CALLER_MEMBER_ID = UUID.randomUUID();

  private Server grpcServer;
  private ManagedChannel grpcChannel;
  private TripClient tripClient;
  private ExpenseRepository expenseRepository;
  private MockMvc mockMvc;
  private Authentication authentication;

  @BeforeEach
  void setUp() throws Exception {
    expenseRepository = mock(ExpenseRepository.class);
    ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    ExchangeRateProvider exchangeRateProvider = mock(ExchangeRateProvider.class);

    when(expenseRepository.save(any(Expense.class)))
        .thenAnswer(
            inv -> {
              Expense e = inv.getArgument(0);
              e.setId(UUID.randomUUID());
              e.setCreatedAt(Instant.now());
              e.setUpdatedAt(Instant.now());
              return e;
            });

    when(exchangeRateProvider.getRate(any(), any()))
        .thenReturn(new FxQuote(new BigDecimal("0.9220"), RateSource.LIVE, Instant.now()));

    String serverName = InProcessServerBuilder.generateName();
    grpcServer =
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(new MemberAwareTripService())
            .build()
            .start();
    grpcChannel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    tripClient = new TripGrpcClient(TripServiceGrpc.newBlockingStub(grpcChannel));

    ExpenseService service =
        new ExpenseService(expenseRepository, tripClient, eventPublisher, exchangeRateProvider);
    ExpenseController controller = new ExpenseController(service);

    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    authentication =
        new UsernamePasswordAuthenticationToken(
            DEVICE_ID, "", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    SecurityContextHolder.clearContext();
    grpcChannel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    grpcServer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
  }

  private String foreignCurrencyBody() {
    return """
    {
      "amount": 42.00,
      "currency": "USD",
      "category": "FOOD",
      "description": "Foreign meal",
      "splitMode": "EQUAL"
    }
    """;
  }

  @Test
  void create_foreignCurrency_persistsReferenceCurrencyFromTripService() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/trips/{tripId}/expenses", TRIP_ID)
                .principal(authentication)
                .header("X-Device-Id", DEVICE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(foreignCurrencyBody()))
        .andExpect(status().isCreated());

    ArgumentCaptor<Expense> cap = ArgumentCaptor.forClass(Expense.class);
    verify(expenseRepository).save(cap.capture());
    Expense saved = cap.getValue();

    assertThat(saved.getCurrency()).isEqualTo("USD");
    assertThat(saved.getReferenceCurrency()).isEqualTo("EUR");
    assertThat(saved.getExchangeRate()).isEqualByComparingTo("0.9220");
  }

  @Test
  void create_byNonMember_returns403_withNoDbWrite() throws Exception {
    UUID otherTrip = UUID.randomUUID();

    mockMvc
        .perform(
            post("/api/v1/trips/{tripId}/expenses", otherTrip)
                .principal(authentication)
                .header("X-Device-Id", DEVICE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(foreignCurrencyBody()))
        .andExpect(status().isForbidden());

    verify(expenseRepository, never()).save(any(Expense.class));
  }

  /**
   * In-process trip-service implementation knowing about exactly one (TRIP_ID, DEVICE_ID,
   * CALLER_MEMBER_ID) triplet. IsMember populates tripMemberId so requireMembership in the
   * production path can build a non-null TripMembership.tripMemberId().
   */
  private static class MemberAwareTripService extends TripServiceGrpc.TripServiceImplBase {

    @Override
    public void isMember(IsMemberRequest request, StreamObserver<IsMemberResponse> observer) {
      boolean match =
          TRIP_ID.toString().equals(request.getTripId()) && DEVICE_ID.equals(request.getDeviceId());
      IsMemberResponse.Builder b = IsMemberResponse.newBuilder().setIsMember(match);
      if (match) {
        b.setRole("PARTICIPANT").setTripMemberId(CALLER_MEMBER_ID.toString());
      } else {
        b.setRole("");
      }
      observer.onNext(b.build());
      observer.onCompleted();
    }

    @Override
    public void getTripCurrency(
        GetTripCurrencyRequest request, StreamObserver<GetTripCurrencyResponse> observer) {
      observer.onNext(GetTripCurrencyResponse.newBuilder().setCurrencyCode("EUR").build());
      observer.onCompleted();
    }

    @Override
    public void getTripMembers(
        GetTripMembersRequest request, StreamObserver<GetTripMembersResponse> observer) {
      if (!TRIP_ID.toString().equals(request.getTripId())) {
        observer.onNext(GetTripMembersResponse.newBuilder().build());
        observer.onCompleted();
        return;
      }
      observer.onNext(
          GetTripMembersResponse.newBuilder()
              .addMembers(
                  TripMemberProto.newBuilder()
                      .setDisplayName("Alice")
                      .setRole("PARTICIPANT")
                      .setTripMemberId(CALLER_MEMBER_ID.toString())
                      .build())
              .build());
      observer.onCompleted();
    }
  }
}
