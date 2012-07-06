load('vertx.js')

load('wibble6.js')

vertx.createHttpServer().requestHandler(function(req) {
    req.response.end("<html><body><h1>Hello matron ccom vert.x3 " + wibble() + " </h1></body></html>");
}).listen(8080, 'localhost');
