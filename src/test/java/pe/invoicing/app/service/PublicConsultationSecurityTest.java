package pe.invoicing.app.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;
import pe.invoicing.app.config.security.filter.AuthFilter;
import pe.invoicing.app.service.authentication.AutenticacionService;
import jakarta.servlet.FilterChain;
import static org.mockito.Mockito.*;

class PublicConsultationSecurityTest {
  @ParameterizedTest @ValueSource(strings={"", "/status", "/PDF", "/XML", "/CDR"})
  void publicGetRequiresDocumentKeyButNoAccountToken(String suffix) throws Exception {
    var auth=mock(AutenticacionService.class);var errors=mock(HandlerExceptionResolver.class);var chain=mock(FilterChain.class);
    var request=new MockHttpServletRequest("GET","/public/documents/"+"a".repeat(32)+suffix);
    var response=new MockHttpServletResponse();
    new AuthFilter(auth,errors).doFilter(request,response,chain);
    verify(chain).doFilter(request,response);verifyNoInteractions(auth,errors);
  }

  @ParameterizedTest @ValueSource(strings={"/companies/20123456786/documents", "/v2/companies/20123456786/sales", "/public/documents/invalid/status"})
  void privateOrInvalidPathsStillRequireAuthentication(String path) throws Exception {
    var auth=mock(AutenticacionService.class);var errors=mock(HandlerExceptionResolver.class);var chain=mock(FilterChain.class);
    var request=new MockHttpServletRequest("GET",path);var response=new MockHttpServletResponse();
    new AuthFilter(auth,errors).doFilter(request,response,chain);
    verify(errors).resolveException(eq(request),eq(response),isNull(),any());verifyNoInteractions(chain);
  }
}
