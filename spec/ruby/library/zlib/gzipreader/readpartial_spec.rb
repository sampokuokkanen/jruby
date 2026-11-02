require_relative '../../../spec_helper'
require 'stringio'
require 'zlib'

describe "Zlib::GzipReader#readpartial" do
  before :each do
    @data = '12345abcde'
    @zip = [31, 139, 8, 0, 44, 220, 209, 71, 0, 3, 51, 52, 50, 54, 49, 77,
            76, 74, 78, 73, 5, 0, 157, 5, 0, 36, 10, 0, 0, 0].pack('C*')
    @io = StringIO.new(@zip)
  end

  it 'accepts nil buffer' do
    gz = Zlib::GzipReader.new(@io)
    gz.readpartial(5, nil).should == '12345'
  end

  it "reads the contents into the buffer and returns it" do
    gz = Zlib::GzipReader.new(@io)
    buffer = +"old content"
    gz.readpartial(5, buffer).should.equal?(buffer)
    buffer.should == '12345'
  end

  it "keeps the encoding of the buffer" do
    gz = Zlib::GzipReader.new(@io)
    buffer = String.new(encoding: Encoding::UTF_8)
    gz.readpartial(5, buffer).encoding.should == Encoding::UTF_8
  end

  it "raises TypeError if the buffer is not a String" do
    gz = Zlib::GzipReader.new(@io)
    -> { gz.readpartial(5, :buffer) }.should.raise(TypeError)
  end

  it "raises EOFError at the end of data" do
    gz = Zlib::GzipReader.new(@io)
    gz.read
    -> { gz.readpartial(5) }.should.raise(EOFError)
  end

  it "clears the buffer and raises EOFError at the end of data" do
    gz = Zlib::GzipReader.new(@io)
    gz.read
    buffer = +"old content"
    -> { gz.readpartial(5, buffer) }.should.raise(EOFError)
    buffer.should == ""
  end
end
