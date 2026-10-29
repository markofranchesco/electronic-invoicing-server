# Fábrica Abstracta S.A.C.S. — Facturación electrónica

Java 21 y Maven Wrapper. Pruebas: `bash mvnw clean verify`. Inicio: `bash mvnw spring-boot:run`. Windows: `mvnw.cmd`.

## Venta directa al PDF

Tras configurar cuenta y empresa, usar `POST /v2/companies/{ruc}/sales?format=TICKET_80` con token e `Idempotency-Key` (8–200 caracteres). El cuerpo contiene solamente `document`, con el detalle de venta mostrado en Postman. **Ya no se envía `send` ni se confirma la venta después.**

- **200 application/pdf:** guardar/imprimir el comprobante. Cabeceras `X-Document-Id` y `X-Emission-Status` identifican el resultado.
- **202 application/json:** la emisión demora o aún no tiene PDF. Incluye `documentId`, `message`, `recoveryUrl` y `document` con el estado interno. La venta queda registrada; no crear otra ni cambiar la clave.
- **409:** rechazo, anulación, revisión manual o conflicto. Revisar el mensaje antes de reintentar.

La espera del trabajo externo se configura con `app.sales.wait-timeout-ms` / `SALES_WAIT_TIMEOUT_MS`, 10000 ms por defecto (1–120000). Al vencer, retorna 202 y el proceso puede continuar. No se cancela la venta ni se duplica el envío. La persistencia y los tiempos de conexión no están incluidos en un plazo absoluto de diez segundos.

Solo ante demora/falla: `POST /v2/companies/{ruc}/sales/{id}/recover?format=TICKET_80`. Reutiliza la venta, correlativo y XML existentes. La ruta V2 `/send` se sustituyó por `/recover`; importar la colección nueva. El conciliador mantiene la recuperación tras caídas. Las reservas internas necesarias para esto no son un paso manual de confirmación.

Un PDF de boleta puede estar disponible tras enviar el resumen, antes de recibir su CDR. `SUBMITTED` no equivale a aceptación tributaria. El estado sigue disponible en la API y en la página de consulta; no se imprime la frase operativa en el ticket normal. Rechazos, anulaciones, borradores y emisiones no confirmadas mantienen advertencias para no presentarlos como comprobantes válidos.

## Ticket y consulta del cliente

Diseño térmico: logo, emisor, RUC/dirección, serie/correlativo, cliente, columnas de cantidad/descripción/precio/total, impuestos, pago, QR fiscal y pie de consulta. `TICKET_80` y `TICKET_54` disponibles; se mantienen A4 y los demás anchos anteriores. Fuente incorporada al PDF. No se redondea el precio unitario de más de dos decimales para ocultar su precisión.

Configurar en propiedades o variables de entorno:

| Propiedad | Variable | Contenido |
|---|---|---|
| `app.receipt.public-base-url` | `PUBLIC_BASE_URL` | URL pública del backend que sirve los comprobantes, sin barra final |
| `app.sales.wait-timeout-ms` | `SALES_WAIT_TIMEOUT_MS` | Espera del trabajo externo; predeterminado 10000 |

La URL pública es el dominio de esta API, no un proveedor externo. Si está vacía, no se imprime el enlace. Configurarla para mostrar la consulta. El pie identifica a Fábrica Abstracta S.A.C.S.

La publicación utiliza `/public/documents/{clave}`: página real con identidad del comprobante, estado y descargas de PDF/XML/CDR. La clave específica, aleatoria y no predecible permite acceder a ese comprobante sin iniciar sesión; compartirla solo con el adquirente. La V2 publica el comprobante antes de devolver el PDF. Reimpresión no vuelve a emitir ni cobrar.

El **QR fiscal no es una URL**: contiene RUC, tipo, serie, número, IGV, total, fecha, documento del cliente y DigestValue del XML. Se genera en UTF-8, nivel Q, con zona libre. El resumen de firma está dentro del QR y ya no aparece como cadena larga impresa. La URL de consulta se imprime aparte, es clicable en el PDF y tiene su propio QR web identificado. El QR por sí solo no acredita aceptación SUNAT.

Referencia técnica: [Anexo VI de la RS 114-2019, SEE del contribuyente, sección 6.4](https://www.sunat.gob.pe/legislacion/superin/2019/anexoVI-114-2019.pdf).

## Producción y datos impresos

Se eliminaron las observaciones “Venta de demostración…” y los nombres “DEMO/DEMOSTRACIÓN” de los ejemplos comerciales de Postman. El renderer no agrega esas frases. Una venta histórica conserva emisor/cliente/observaciones registrados: no se alteran el XML firmado ni los datos fiscales de comprobantes anteriores para cambiar su apariencia.

El PDF y la página de consulta no muestran advertencias de beta, desarrollo ni entorno. Su presentación es la misma en todos los perfiles. Los perfiles conservan su función interna de conectar con el servicio SUNAT correspondiente; la apariencia no cambia ese destino. No se cambiaron perfiles de base de datos ni se añadieron migraciones.

`logoBase64` sigue siendo por empresa: PNG/JPEG puro, máximo 512 KiB y 2048 × 2048. Se conserva una copia en cada comprobante. El campo de texto `TaxpayerEntity.logoBase64` incorporado en la entrega anterior debe existir en producción; esta revisión no añade columnas.

## Postman por función

Importar `electronic-invoicing.json`. Su título coincide con OpenAPI. No ejecutar toda la colección de corrido: escoger la operación.

1. **Configuración inicial:** crear cuenta, iniciar sesión, guardar empresa y verificarla.
2. **Ventas directas:** ejemplos de boleta de minimarket, pollería en 54 mm, factura dental al crédito, ferretería y sucursal. Elegir una solicitud, no repetir la configuración.
3. **Consultas y paginación:** boletas/facturas, estado local y consulta fiscal. Variables `pagina=1`, `porPagina=20`.
4. **Reimpresión y consulta del cliente:** PDF 80/54 mm, publicación y página del comprobante.
5. **Recuperación:** emisión demorada y reanudación tras resolver revisión manual.
6. **Anulaciones mediante nota de crédito:** motivo 01 para ventas otorgadas y que cumplen las reglas fiscales. La baja de comprobantes no otorgados se mantiene en V1/OpenAPI; no debe usarse declarando falsamente que la venta V2 no se entregó.
7. **Ventas al crédito y abonos:** saldo, pago y reversión de un pago incorrecto.

`idVenta` se captura automáticamente al emitir. Para otra venta vaciar `idEjecucion`; para reintentar conservarlo junto con cuerpo y fecha. `idAbono` e `idAnulacion` identifican sus respectivas intenciones: conservarlos al reintentar, vaciarlos para una operación nueva. Los IDs no se inventan: se capturan de respuestas. Configurar razonSocial, nombreComercial, datos de clientes, certificado y URLs del despliegue.

## Sustento

Pruebas de lógica, contratos, firma/XML, recuperación, timeout sin cancelación/duplicación, PDF y publicación. Las pruebas nuevas renderizan los PDFs de 54/80 mm y A4, decodifican el QR y comparan sus campos y digest con el XML firmado; ejercitan las rutas de consulta y descarga con HTTP simulado. La colección se contrasta con OpenAPI y scripts con respuestas controladas.

No se emitieron documentos reales ante SUNAT ni se verificó un dominio productivo todavía no configurado. Pruebas con repositorios/transporte sustituidos no acreditan el bloqueo real de PostgreSQL ni capacidad del despliegue. V1 se conserva. El ZIP contiene código, pruebas, Maven Wrapper, OpenAPI y colección, sin tools, verification, cachés ni compilados.

## Resolver «Empresa no encontrada» y probar la colección

El RUC de una URL no registra una empresa: debe existir dentro de la cuenta del token. Ejecutar la carpeta **01 · Configuración inicial** completa: crear cuenta, iniciar sesión, guardar empresa (200) y verificar empresa (200). Después elegir una venta. El cuarto paso muestra `documentNumber`, `id` y `accountId`; el listado de empresas también incluye ahora el RUC.

La colección usa `tokenAcceso` guardado por el inicio de sesión, evitando un token antiguo del entorno. Antes de cada operación sobre documentos/ventas hace un GET de verificación: si faltan empresa, sesión o identificador, detiene la solicitud y explica qué paso falta. Es una comprobación de Postman, no una nueva confirmación de venta en la API. Requiere una versión de Postman con `pm.execution.skipRequest`.

El perfil de desarrollo existente usa `jdbc:h2:mem:invoicing` y `ddl-auto: create`: **reiniciar la aplicación elimina las cuentas y empresas locales**. Volver a ejecutar los cuatro pasos después de reiniciar. No se modificó esa configuración ni PostgreSQL. No se puede atribuir un 404 del servidor del usuario a una causa específica sin revisar el resultado de su alta y sesión.

## Escanear un QR y consultar por GET

Cuando se configura `PUBLIC_BASE_URL`, el ticket incluye **dos códigos identificados**: QR fiscal con los datos reglamentarios y QR «Consultar comprobante» con la URL real `GET /public/documents/{clave}`. El segundo abre la información y las descargas en el navegador. En 80 mm se muestran juntos y en 54 mm uno debajo del otro. Sin dominio configurado no se inventa un QR web.

Para consultar desde otra API/Postman: `GET /public/documents/{clave}/status` devuelve JSON con RUC, serie, número, fecha, total, estado, código SUNAT y disponibilidad de XML/CDR. No afirma aceptación si la respuesta continúa pendiente. La clave de publicación identifica exclusivamente ese comprobante.

Prueba en el mismo equipo: `PUBLIC_BASE_URL=http://localhost:8080`. Para escanear desde el celular usar el dominio o una dirección de red accesible del servidor, porque `localhost` en el celular apunta al propio celular. La carpeta de reimpresión permite obtener la publicación, abrir el GET HTML y consultar el GET JSON.

También se ejecutó el JAR y se recorrieron los endpoints por HTTP local usando los cuerpos de la colección: cuenta, sesión, 404 esperado antes del alta, registro/verificación/listado de empresa, venta con PDF, publicación, consulta HTML/JSON, reimpresión y paginación. El servicio SUNAT fue sustituido por un servidor SOAP local; se comprobó un solo envío de resumen. Esta prueba no representa aceptación tributaria en SUNAT real.
