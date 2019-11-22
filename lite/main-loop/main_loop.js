var console = require('console');
var http = require('http');
var fs = require('fs');

var ant = require('ant');

var RESULT_SUCCESS = "Success";
var RESULT_FAILED = "Failed";

function parseUrl(url) {
  var urlTokens = url.split('/');
  var uniqueTokens = [];
  for (i in urlTokens) {
    var token = urlTokens[i];
    if (token.length > 0) {
      uniqueTokens.push(token);
    }
  }
  return uniqueTokens;
}

function onAliveRequest(request, data) {
  var results = {
    message: "Alive",
    code: 200
  };
  return results;
}

function onAppCommand(request, data) {
  var results = {
    message: "Invalid command",
    code: 500
  };
  var command = data.toString();
  if (command == "start") {
    results = onStartApp(request, data);
  } else if (command == "stop") {
    results = onStopApp(request, data);
  }
  return results;
}

var current_app_object = undefined;
function onInstallApp(request, data) {
  var results = {
    message: RESULT_FAILED,
    code: 500
  };

  // Write app code
  var fd = fs.openSync(APP_JS_FILENAME, 'w');
  fs.writeSync(fd, appCode, 0, appCode.length);
  fs.closeSync(APP_JS_FILENAME);

  // Execute app code with initializaiton function
  current_app_object = require(APP_JS_FILENAME);

  if (ant.runtime.getCurrentApp() !== undefined) {
    results.message = RESULT_SUCCESS;
    results.code = 200;
  }
  return results;
}

function onRemoveApp(request, data) {
  var results = {
    message: "Not yet implemented",
    code: 500
  };
  // TODO: not yet implemented
  return results;
}

function onStartApp(request, data) {
  var results = {
    message: RESULT_FAILED,
    code: 500
  };
  var app = ant.runtime.getCurrentApp();
  if (app != undefined) {
    results.message = app.start();
    if (results.message == RESULT_SUCCESS) {
      results.code = 200;
    }
  }
  return results;
}

function onGetAppInfo(request, data) {
  var currentApp = ant.runtime.getCurrentApp();
  var results = {
    message: "No App Found",
    code: 500
  };

  if (currentApp != undefined) {
    var appInfo = currentApp.getInfo();
    results.message = JSON.stringify(appInfo);
    results.code = 200;
  }
  return results;
}

function onStopApp(request, data) {
  var results = {
    message: "Not yet implemented",
    code: 500
  };
  // TODO: not yet implemented
  return results;
}

function _onHTTPRequest(request, response, data) {
  response.setHeader('Content-Type', 'text/html');
  var urlTokens = parseUrl(request.url);
  var results = {
    message: "Not Found Entry",
    code: 404
  };

  if (urlTokens.length == 0) {
    // "/*"
    if (request.method == "GET") {
      // GET "/": Alive message
      results = onAliveRequest(request, data);
    }
  } else if (urlTokens[1] == "runtime") {
    // "/runtime*"
    if (urlTokens.length == 1) {
      // "/runtime": Not found
    } else if (urlTokens[2] == "currentApp") {
      // "/runtime/currentApp*"
      if (urlTokens.length == 2) {
        // "/runtime/currentApp"
        if (request.method == "GET") {
          // GET "/runtime/currentApp"
          results = onGetAppInfo(request, data);
        } else if (request.method == "POST") {
          // POST "/runtime/currentApp"
          results = onInstallApp(request, data);
        } else if (request.method == "DELETE") {
          // DELETE "/runtime/currentApp"
          results = onRemoveApp(request, data);
        }
      } else if (urlTokens[3] == "command") {
        // "/runtime/currentApp/command"
        if (request.method == "POST") {
          // POST "/runtime/currentApp/command"
          results = onAppCommand(request, data);
        }
      } else if (urlTokens[3] == "code") {
        // "/runtime/currentApp/code"
        if (request.method == "GET") {
          // GET "/runtime/currentApp/code"
          results = onGetAppCode(request, data);
        }
      }
    }
  }

  response.setHeader('Content-Length', responseMessage.length);
  response.writeHead(results.code);
  response.write(results.message);
  response.end();
}

function onHTTPRequest(request, response) {
  console.log('Request for path: ' + request.url);

  var parts = [];
  response.on('data', function (chunk) {
    parts.push(chunk);
  });
  response.on('end', function () {
    var body = Buffer.concat(parts);
    console.log(body.toString());
    _onHTTPRequest(request, response, data);
  });
}

function mainLoop() {
  console.log('main loop start');

  var server = http.createServer();
  server.on('request', onHTTPRequest);

  var port = 8001;
  server.listen(port, function () {
    console.log("ANT core listening on port: " + port);
  });
}

mainLoop();