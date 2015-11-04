#!/usr/bin/env groovy
import org.codehaus.groovy.control.CompilerConfiguration
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

import java.util.regex.Matcher
import java.util.regex.Pattern

@Grapes(@Grab('org.jsoup:jsoup:1.6.1'))
class Request {
    String url;

    public Request clone() {
        def r = new Request()
        r.url = this.url == null ? null : this.url.concat("")
        return r
    }
}

class Response {
    String content;

    Response clone() {
        def r = new Response()
        r.content = this.content == null ? null : this.content.concat("")
        return r
    }
}

class Message {
    Request request;
    Response response;
    Object results;

    public Message clone() {
        def r = new Message()
        r.response = this.response == null ? null : this.response.clone()
        r.request = this.request == null ? null : this.request.clone()
        r.results = this.results
        return r
    }
}


def cli = new CliBuilder()
def options = cli.parse(args)

if (options.arguments().size() < 2) {
    println "You must pass the name of a crawlerfile and a start input"
    println "e.g."
    println "./crawler.groovy tripadvisor.crawlerfile http://www.tripadvisor.com/AllLocations-g1-Places-World.html"
}

def crawlerfilename = options.arguments().first()
def crawlerStartInputUrl = options.arguments().get(1)
def crawlerStartInput = new Message()
crawlerStartInput.request = new Request()
crawlerStartInput.request.url = crawlerStartInputUrl

enum EmittedType {
    value, object, array
}

class Emitted {
    Closure sendTo
    EmittedType type = EmittedType.value

    public void to(Closure sendTo) {
        this.sendTo = sendTo
    }

    public List<Message> build(Message input) {
        def res = new ArrayList<>()
        res.add(input.clone())
        return res
    }

}

class ArrayEmitted extends Emitted {
    EmittedType type = EmittedType.array
}

class LinkEmitted extends Emitted {
    EmittedType type = EmittedType.array
    public List<Message> build(Message input) {
        def res = new ArrayList<>()
        input.results.each { i ->
            Message msg = new Message()
            msg.request = new Request()
            msg.request.url = i
            res.add(msg)
        }
        return res
    }
}

class ObjectEmitted extends Emitted {
    EmittedType type = EmittedType.object
    Map<Object, Closure> fields;

    public Emitted fields(Map<Object, Closure> fields) {
        this.fields = fields
        return this
    }

    public List<Message> build(Message input) {
        Map<Object, Object> object = new LinkedHashMap()
        this.fields.each { k, v ->
            Step s = new Parse()
            v = v.rehydrate(v.delegate, s, v.thisObject)
            v.call()
            Message sres = s.exec(input)
            def res = new ArrayList()
            List<Emitter> emitters = s.getOutputs()
            for (Emitter e : emitters) {
                def msg = e.getFirstEmitter().getValue(sres)
                Emitted emitted = e.getLastEmitter().emitted;
                List<Message> lmsg = emitted.build(msg)
                lmsg.forEach {
                    r -> res.addAll(r.results)
                }
                if(emitted.type.equals(EmittedType.value)){
                    if( res.size()>0 ){
                        res = res.get(0)
                    } else {
                        res = null
                    }
                }
            }
            object.put(k, res)
        }
        Message ret = input.clone()
        ret.results = object
        List<Message> rets = new ArrayList<>()
        rets.add(ret)
        return rets
    }
}

class Emitter {
    Emitted emitted
    Closure action = {
        println "Default emitter action closure call"
        return it
    }
    Emitter nextEmitter
    Emitter previousEmitter

    public Message getValue(Message input) {
        Message output = action(input)
        if (nextEmitter != null) {
            output = nextEmitter.getValue(output)
        }
        return output
    }

    public Emitter getLastEmitter() {
        if (this.nextEmitter == null) {
            return this
        }
        return this.nextEmitter.getLastEmitter()
    }

    public Emitter getFirstEmitter() {
        if (this.previousEmitter == null) {
            return this;
        }
        return this.previousEmitter.getFirstEmitter();
    }
}

abstract class Step {
    String name
    Emitter currentEmitter = null
    List<Emitter> outputs = new ArrayList<>()

    public Emitted emit(Closure emittedFactory) {
        if (currentEmitter == null) {
            currentEmitter = new Emitter()
        }
        def emitted = emittedFactory()
        currentEmitter.emitted = emitted
        outputs.add(currentEmitter)
        currentEmitter = null
        return emitted
    }

    public void recipe(Closure c) {
        c.delegate = this
        c.call()
    }

    public void internalApply(Closure closure) {
        Emitter e = new Emitter()
        e.action = closure
        if (currentEmitter != null) {
            e.previousEmitter = currentEmitter
            currentEmitter.nextEmitter = e
        }
        currentEmitter = e
    }

    public void apply(Closure c) {
        internalApply {
            what ->
                println "external apply emitter action call"
                List<Object> obj = new ArrayList<>()
                what.results.each { r ->
                    def res = c.call(r)
                    if (res != null) {
                        obj.add(res)
                    }
                }
                def ret = ((Message) what).clone()
                ret.results = obj
                return ret
        }
    }

    public void attributeSelector(String selector) {
        internalApply {
            what ->
                println "attributeSelector emitter action closure call with arg : " + selector
                List<Object> obj = new ArrayList<>()
                what.results.each { r ->
                    if (r != null) {
                        Document doc = Jsoup.parse(r)
                        if (doc.body().children() != 0) {
                            doc.body().children().each { e ->
                                if (e != null) {
                                    obj.add(e.attr(selector))
                                }
                            }
                        }
                    }
                }
                def ret = ((Message) what).clone()
                ret.results = obj
                return ret
        }
    }

    public void cssSelector(String selector) {
        internalApply {
            what ->
                println "cssSelector emitter action closure call with arg : " + selector
                List<Object> obj = new ArrayList<>()
                what.results.each { r ->
                    if (r != null) {
                        Document doc = Jsoup.parse(r)
                        doc.select(selector).each { e ->
                            obj.add(e.outerHtml())
                        };
                    }
                }
                def ret = ((Message) what).clone()
                ret.results = obj
                return ret
        }
    }

    public void regexp(String regexp) {
        internalApply {
            what ->
                println "regexp emitter action closure call with arg : " + regexp
                List<Object> obj = new ArrayList<>()
                what.results.each { r ->
                    Pattern p = Pattern.compile(regexp);
                    Matcher m = p.matcher(r);
                    while (m.find()) {
                        obj.add(m.group())
                    }
                }
                def ret = ((Message) what).clone()
                ret.results = obj
                return ret
        }
    }

    public void extractCrawledUrl() {
        internalApply {
            what ->
                println "extractCrawledUrl emitter action closure call "
                List<Object> obj = new ArrayList<>()
                what.results.each {
                    obj.add(what.request.url)
                }

                def ret = ((Message) what).clone()
                ret.results = obj
                return ret
        }
    }

    public void toFirstElement() {
        internalApply {
            what ->
                println "toFirstElement emitter action closure call"
                def ret = ((Message) what).clone()
                ret.results = what.results.get(0)
                return ret
        }
    }

    public void textCssSelector(String selector) {
        internalApply {
            what ->
                println "textCssSelector emitter action closure call with arg : " + selector
                List<Object> obj = new ArrayList<>()
                what.results.each { r ->
                    if (r != null) {
                        Document doc = Jsoup.parse(r)
                        doc.select(selector).each { e ->
                            obj.add(e.text())
                        };
                    }
                }
                def ret = ((Message) what).clone()
                ret.results = obj
                return ret
        }
    }

    public void prepend(String pre) {
        internalApply {
            what ->
                println "prepend emitter action closure call with arg : " + pre
                List<Object> obj = new ArrayList<>()
                what.results.each { r ->
                    if (r != null) {
                        obj.add(pre.concat(r.toString()))
                    }
                }
                def ret = ((Message) what).clone()
                ret.results = obj
                return ret
        }
    }


    public abstract Message exec(Message s)
}

class Download extends Step {

    public Message exec(Message input) {
        println "downloading url " + input.request.url
        Message m = (Message) input;
        Message result = input.clone();
        result.response = new Response()
        result.response.content = new URL(m.request.url).getText()
        return result
    }
}

class Parse extends Step {
    public Message exec(Message input) {
        println "=====parsing content ========\n" + input.response.content +"\n\n=================================\n"
        List<Object> array = new ArrayList()
        array.add(input == null || input.response == null || input.response.content == null ? "" : input.response.content.concat(""))
        Message r = input.clone()
        r.results = array
        return r;
    }
}

class Result extends Step {
    public List<Object> results = new ArrayList<>()
    public Message exec(Message input){
        results.addAll(input.results)
        return null
    }
}

abstract class Crawlerfile extends Script {
    LinkedHashMap<String, Object> download = new LinkedHashMap<>()
    LinkedHashMap<String, Object> parse = new LinkedHashMap<>()
    Step results = new Result()


    def getStartStepClosure = {}
    def link = { return new LinkEmitted() }
    def page = { return new Emitted() }
    def array = { return new ArrayEmitted() }
    def object = { return new ObjectEmitted() }
    def value = { return new Emitted() }

    public Step getStartStep() {
        return getStartStepClosure.call()
    }

    public Download download(String name) {
        def step = download.get(name);
        if (step == null) {
            step = new Download()
            step.name = name
            download.put(name, step)
        }
        return step
    }

    public Parse parse(String name) {
        def step = parse.get(name);
        if (step == null) {
            step = new Parse()
            step.name = name
            parse.put(name, step)
        }
        return step
    }

    Closure result = { it ->
        return results;
    }

    public Closure start(Closure getStartStep) {
        getStartStepClosure = getStartStep
    }

}

def crawlerConfig = new CompilerConfiguration();
crawlerConfig.scriptBaseClass = Crawlerfile.class.getName();

def shell = new GroovyShell(this.class.classLoader, crawlerConfig)

def crawlerfileContents = new File(crawlerfilename).getText('UTF-8')

def crawlerfile = shell.evaluate(crawlerfileContents + "\nreturn this\n")

public class StepExecution {
    Step step
    Message input

    StepExecution clone() {
        StepExecution r = new StepExecution()
        r.step = step;
        r.input = input.clone()
        return r
    }
}

public class CrawlerfileExecutor {

    public LinkedList<StepExecution> past = new ArrayList<>();
    public LinkedList<StepExecution> future = new ArrayList<>();
    public LinkedList<StepExecution> nsteps = new LinkedList<>()

    public void run(Crawlerfile crawlerfile, Object crawlerStartInput) {
        StepExecution exec = new StepExecution()
        exec.input = crawlerStartInput
        exec.step = crawlerfile.getStartStep()
        nsteps = new LinkedList<>()
        nsteps.push(exec)
        resume()
    }

    public List<StepExecution> runStep(StepExecution current) {
        past.add(current.clone())
        Message output = current.step.exec(current.input)
        if(output == null){
            return new ArrayList<StepExecution>();
        }
        def res = new ArrayList()
        List<Emitter> emitters = current.step.getOutputs()
        for (Emitter e : emitters) {
            List<StepExecution> stepExecutions = new ArrayList<>()
            Message msg = e.getFirstEmitter().getValue(output)
            Emitted emitted = e.getLastEmitter().getEmitted()
            Step step = emitted.sendTo();
            List<Message> lmsg = emitted.build(msg)
            lmsg.each { msgitem ->
                StepExecution exec = new StepExecution()
                exec.input = msgitem
                exec.step = step
                stepExecutions.add(exec)
            }
            res.addAll(stepExecutions)
        }
        return res
    }

    public StepExecution back() {
        if (past.size() == 0) {
            println "cannot go back, already at the beginning"
            return null
        }
        StepExecution last = past.pop()
        future.push(last)
        return last
    }

    public StepExecution next() {
        if (future.size() == 0) {
            println "cannot go forward, already at the end"
            return null
        }
        StepExecution next = future.pop()
        past.push(next)
        return next
    }

    public void runLast() {
        StepExecution e = back()
        if (e != null) {
            nsteps = runStep(e)
        }
    }
    public void runNext(){
        StepExecution e = next()
        if (e != null) {
            nsteps = runStep(e)
        }
    }
    public void resume(){
        while (nsteps.size() > 0) {
            runStep(nsteps.pop()).each {
                s -> nsteps.push(s)
            }
        }
    }
}

new CrawlerfileExecutor().run(crawlerfile, crawlerStartInput)